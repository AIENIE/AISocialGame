package com.aisocialgame.service.ai;

import com.aisocialgame.config.AppProperties;
import com.aisocialgame.config.PromptProperties;
import com.aisocialgame.integration.grpc.client.AiGrpcClient;
import com.aisocialgame.integration.grpc.dto.AiChatMessageDto;
import com.aisocialgame.integration.grpc.dto.AiChatResult;
import com.aisocialgame.model.AiDecisionTrace;
import com.aisocialgame.model.GameLogEntry;
import com.aisocialgame.model.GamePlayerState;
import com.aisocialgame.model.GameState;
import com.aisocialgame.model.Persona;
import com.aisocialgame.repository.PersonaRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
public class AiDecisionService {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final AiGrpcClient aiGrpcClient;
    private final AppProperties appProperties;
    private final PromptProperties promptProperties;
    private final PersonaRepository personaRepository;
    private final AiBeliefService aiBeliefService;
    private final AiQualityService aiQualityService;
    private final AiReflectionService aiReflectionService;
    private final AiDecisionTraceService aiDecisionTraceService;

    public AiDecisionService(AiGrpcClient aiGrpcClient,
                             AppProperties appProperties,
                             PromptProperties promptProperties,
                             PersonaRepository personaRepository,
                             AiBeliefService aiBeliefService,
                             AiQualityService aiQualityService,
                             AiReflectionService aiReflectionService,
                             AiDecisionTraceService aiDecisionTraceService) {
        this.aiGrpcClient = aiGrpcClient;
        this.appProperties = appProperties;
        this.promptProperties = promptProperties;
        this.personaRepository = personaRepository;
        this.aiBeliefService = aiBeliefService;
        this.aiQualityService = aiQualityService;
        this.aiReflectionService = aiReflectionService;
        this.aiDecisionTraceService = aiDecisionTraceService;
    }

    public AiDecisionResult generateSpeech(GameState state, GamePlayerState actor) {
        DecisionInput input = decisionInput(state, actor, "SPEECH");
        AiGameContext context = input.context();
        String fallback = fallbackSpeech(context);
        CallResult call = callAndParse(context, promptFor(context), fallback);
        AiDecisionResult result = call.payload()
                .map(payload -> {
                    String content = sanitizeText(asString(payload.get("content")), 90);
                    AiDecisionResult parsed = StringUtils.hasText(content) ? AiDecisionResult.speech(content, false) : AiDecisionResult.speech(fallback, true);
                    return withPayloadQuality(parsed, payload);
                })
                .orElseGet(() -> AiDecisionResult.speech(fallback, true));
        return finalizeDecision(state, actor, "SPEECH", input, result, call);
    }

    public AiDecisionResult decideVote(GameState state, GamePlayerState actor) {
        DecisionInput input = decisionInput(state, actor, "VOTE");
        AiGameContext context = input.context();
        String fallbackTarget = fallbackVoteTarget(context);
        CallResult call = callAndParse(context, promptFor(context), "");
        AiDecisionResult result = call.payload()
                .map(payload -> {
                    Integer targetSeat = asInteger(payload.get("targetSeat"));
                    String targetId = playerIdBySeat(context, targetSeat);
                    boolean usedFallback = false;
                    if (!StringUtils.hasText(targetId)) {
                        targetId = fallbackTarget;
                        usedFallback = true;
                    }
                    return withPayloadQuality(AiDecisionResult.vote(targetId, sanitizeText(asString(payload.get("reason")), 80), usedFallback), payload);
                })
                .orElseGet(() -> AiDecisionResult.vote(fallbackTarget, "规则兜底", true));
        return finalizeDecision(state, actor, "VOTE", input, result, call);
    }

    public AiDecisionResult decideNightAction(GameState state, GamePlayerState actor) {
        DecisionInput input = decisionInput(state, actor, "NIGHT_ACTION");
        AiGameContext context = input.context();
        AiDecisionResult fallback = fallbackNightAction(context);
        CallResult call = callAndParse(context, promptFor(context), "");
        AiDecisionResult result = call.payload()
                .map(payload -> {
                    String action = normalizeAction(asString(payload.get("action")));
                    Integer targetSeat = asInteger(payload.get("targetSeat"));
                    String targetId = playerIdBySeat(context, targetSeat);
                    boolean useHeal = Boolean.TRUE.equals(payload.get("useHeal"));
                    if (!isLegalNightAction(context, action, targetId, useHeal)) {
                        return withPayloadQuality(fallback, payload);
                    }
                    return withPayloadQuality(AiDecisionResult.nightAction(action, targetId, useHeal, sanitizeText(asString(payload.get("reason")), 80), false), payload);
                })
                .orElse(fallback);
        return finalizeDecision(state, actor, "NIGHT_ACTION", input, result, call);
    }

    private DecisionInput decisionInput(GameState state, GamePlayerState actor, String action) {
        Map<String, Object> belief = aiBeliefService.buildBelief(state, actor);
        aiBeliefService.rememberBelief(state, actor, belief);
        Map<String, Object> memory = aiReflectionService.memorySnapshot(state, actor);
        AiGameContext context = buildContext(state, actor, action, belief, memory);
        return new DecisionInput(context, belief, memory);
    }

    private AiDecisionResult finalizeDecision(GameState state,
                                              GamePlayerState actor,
                                              String action,
                                              DecisionInput input,
                                              AiDecisionResult result,
                                              CallResult call) {
        List<String> qualityFlags = aiQualityService.evaluate(state, actor, result);
        String reflection = StringUtils.hasText(result.reflection())
                ? result.reflection()
                : aiReflectionService.summarizeDecision(state, actor, result, qualityFlags);
        aiReflectionService.rememberShortReflection(state, actor, reflection);
        AiDecisionResult enriched = result.withQuality(
                result.confidence(),
                result.evidence(),
                reflection,
                qualityFlags,
                null,
                Map.of()
        );
        AiDecisionTrace trace = null;
        try {
            trace = aiDecisionTraceService.record(
                    state,
                    actor,
                    action,
                    enriched,
                    call.rawOutput(),
                    call.response(),
                    call.latencyMs(),
                    input.belief(),
                    input.memory(),
                    qualityFlags,
                    inputSummary(input.context())
            );
            aiReflectionService.updatePersonaMemory(state, actor, enriched, reflection, qualityFlags);
        } catch (Exception ignored) {
            // AI quality logging must never block a live game action.
        }
        Map<String, Object> metadata = trace != null
                ? aiDecisionTraceService.safeLogMetadata(trace, enriched.evidence(), reflection)
                : Map.of("aiQualityFlags", qualityFlags, "aiFallback", enriched.fallback(), "aiReflection", reflection);
        return enriched.withQuality(enriched.confidence(), enriched.evidence(), reflection, qualityFlags, trace != null ? trace.getId() : null, metadata);
    }

    private AiDecisionResult withPayloadQuality(AiDecisionResult result, Map<String, Object> payload) {
        return result.withQuality(
                asDouble(payload.get("confidence")),
                asStringList(payload.get("evidence")),
                sanitizeText(asString(payload.get("reflection")), 180),
                asStringList(payload.get("qualityNotes")),
                null,
                Map.of()
        );
    }

    private CallResult callAndParse(AiGameContext context, String actionPrompt, String fallbackText) {
        long startedAt = System.nanoTime();
        AiChatResult response = null;
        Map<String, Object> rawOutput = Map.of();
        try {
            String userPrompt = buildUserPrompt(context, actionPrompt);
            response = aiGrpcClient.chatCompletions(
                    appProperties.getProjectKey(),
                    appProperties.getAi().getSystemUserId(),
                    "",
                    appProperties.getAi().getDefaultModel(),
                    List.of(
                            new AiChatMessageDto("system", promptProperties.getAiDecision().getSystemPrompt()),
                            new AiChatMessageDto("user", userPrompt)
                    )
            );
            String content = response.content();
            if (!StringUtils.hasText(content)) {
                content = fallbackText;
            }
            Optional<Map<String, Object>> parsed = parseJsonObject(content);
            rawOutput = parsed.<Map<String, Object>>map(HashMap::new).orElseGet(HashMap::new);
            return new CallResult(parsed, rawOutput, response, elapsedMs(startedAt));
        } catch (Exception ignored) {
            return new CallResult(Optional.empty(), rawOutput, response, elapsedMs(startedAt));
        }
    }

    private AiGameContext buildContext(GameState state, GamePlayerState actor, String action, Map<String, Object> belief, Map<String, Object> memory) {
        Persona persona = personaRepository.findById(actor.getPersonaId());
        List<AiPlayerInfo> players = state.getPlayers().stream()
                .sorted(Comparator.comparingInt(GamePlayerState::getSeatNumber))
                .map(player -> toVisiblePlayerInfo(state, actor, player))
                .toList();
        List<String> speeches = new ArrayList<>();
        List<String> votes = new ArrayList<>();
        List<String> events = new ArrayList<>();
        if (state.getLogs() != null) {
            for (GameLogEntry log : state.getLogs()) {
                String line = log.getMessage();
                if (!StringUtils.hasText(line)) {
                    continue;
                }
                if ("speak".equals(log.getType())) {
                    speeches.add(line);
                } else if ("vote".equals(log.getType())) {
                    votes.add(line);
                } else {
                    events.add(line);
                }
            }
        }
        Map<String, Object> extra = new HashMap<>();
        extra.put("persona", persona != null ? persona.getName() + " / " + persona.getTrait() : "中性 AI");
        if (persona != null) {
            extra.put("personaSpeechStyle", persona.getSpeechStyle());
            extra.put("personaStrategyStyle", persona.getStrategyStyle());
            extra.put("personaDifficultyLevel", persona.getDifficultyLevel());
            extra.put("personaMemorySeed", persona.getMemorySeed());
        }
        extra.put("seerResults", visibleSeerResults(state, actor));
        extra.put("wolfTarget", state.getData().get("wolfTarget"));
        extra.put("lastNightEvents", events.stream().filter(e -> e.contains("今晚") || e.contains("平安夜") || e.contains("天亮")).toList());
        extra.put("belief", belief);
        extra.put("memory", memory);
        return new AiGameContext(
                state.getGameId(),
                state.getPhase(),
                state.getRoundNumber(),
                action,
                toVisiblePlayerInfo(state, actor, actor),
                players,
                speeches,
                votes,
                events,
                extra
        );
    }

    private AiPlayerInfo toVisiblePlayerInfo(GameState state, GamePlayerState actor, GamePlayerState player) {
        String role = null;
        String word = null;
        if (player.getPlayerId().equals(actor.getPlayerId())) {
            role = player.getRole();
            word = player.getWord();
        } else if (!player.isAlive()) {
            role = player.getRole();
        } else if ("werewolf".equals(state.getGameId())
                && actor.getRole() != null
                && actor.getRole().startsWith("WEREWOLF")
                && player.getRole() != null
                && player.getRole().startsWith("WEREWOLF")) {
            role = "WEREWOLF";
        }
        return new AiPlayerInfo(
                player.getPlayerId(),
                player.getDisplayName(),
                player.getSeatNumber(),
                player.isAi(),
                player.isAlive(),
                role,
                word,
                player.getConnectionStatus()
        );
    }

    private Object visibleSeerResults(GameState state, GamePlayerState actor) {
        if (!"SEER".equals(actor.getRole())) {
            return Map.of();
        }
        Object raw = state.getData().get("seerResults");
        if (!(raw instanceof Map<?, ?> results)) {
            return Map.of();
        }
        Object own = results.get(actor.getPlayerId());
        return own != null ? Map.of(actor.getPlayerId(), own.toString()) : Map.of();
    }

    private String promptFor(AiGameContext context) {
        PromptProperties.AiDecision prompts = promptProperties.getAiDecision();
        if ("undercover".equals(context.gameId()) && "SPEECH".equals(context.action())) {
            return prompts.getUndercoverSpeech();
        }
        if ("undercover".equals(context.gameId()) && "VOTE".equals(context.action())) {
            return prompts.getUndercoverVote();
        }
        if ("werewolf".equals(context.gameId()) && "SPEECH".equals(context.action())) {
            return prompts.getWerewolfSpeech();
        }
        if ("werewolf".equals(context.gameId()) && "VOTE".equals(context.action())) {
            return prompts.getWerewolfVote();
        }
        if ("werewolf".equals(context.gameId()) && "NIGHT_ACTION".equals(context.action())) {
            return prompts.getWerewolfNight();
        }
        return prompts.getSystemPrompt();
    }

    private String buildUserPrompt(AiGameContext context, String actionPrompt) throws JsonProcessingException {
        Map<String, Object> payload = new HashMap<>();
        payload.put("instruction", actionPrompt);
        payload.put("gameId", context.gameId());
        payload.put("phase", context.phase());
        payload.put("round", context.round());
        payload.put("action", context.action());
        payload.put("self", context.self());
        payload.put("players", context.players());
        payload.put("speeches", tail(context.speeches(), 20));
        payload.put("votes", tail(context.votes(), 20));
        payload.put("events", tail(context.events(), 20));
        payload.put("extra", context.extra());
        return MAPPER.writeValueAsString(payload);
    }

    private String inputSummary(AiGameContext context) {
        return context.gameId() + "/" + context.phase() + "/round=" + context.round()
                + "/action=" + context.action()
                + "/speeches=" + context.speeches().size()
                + "/votes=" + context.votes().size();
    }

    private Optional<Map<String, Object>> parseJsonObject(String content) throws JsonProcessingException {
        if (!StringUtils.hasText(content)) {
            return Optional.empty();
        }
        String normalized = content.trim();
        int start = normalized.indexOf('{');
        int end = normalized.lastIndexOf('}');
        if (start >= 0 && end > start) {
            normalized = normalized.substring(start, end + 1);
            return Optional.of(MAPPER.readValue(normalized, MAP_TYPE));
        }
        if (normalized.toUpperCase(Locale.ROOT).startsWith("VOTE:")) {
            return Optional.of(Map.of("targetSeat", normalized.substring(5).trim()));
        }
        if (normalized.toUpperCase(Locale.ROOT).startsWith("TARGET:")) {
            return Optional.of(Map.of("targetSeat", normalized.substring(7).trim()));
        }
        return Optional.empty();
    }

    private String fallbackSpeech(AiGameContext context) {
        if ("undercover".equals(context.gameId())) {
            String word = context.self().word();
            if (!StringUtils.hasText(word)) {
                return "这个东西不好直说，我先听听大家怎么描述。";
            }
            return word.length() <= 2 ? "它很常见，但细节上容易和别的东西混淆。" : "它很有辨识度，不过我不想把范围说得太死。";
        }
        if ("werewolf".equals(context.gameId())) {
            return "我先按发言和投票逻辑看，暂时更关注前后说法不一致的人。";
        }
        return "我先观察一下局势，再做判断。";
    }

    private String fallbackVoteTarget(AiGameContext context) {
        return context.players().stream()
                .filter(p -> p.alive() && !p.playerId().equals(context.self().playerId()))
                .sorted(Comparator
                        .comparingInt((AiPlayerInfo p) -> speechLength(context, p.displayName()))
                        .thenComparingInt(AiPlayerInfo::seatNumber))
                .map(AiPlayerInfo::playerId)
                .findFirst()
                .orElse(null);
    }

    private AiDecisionResult fallbackNightAction(AiGameContext context) {
        String role = context.self().role();
        if (role != null && role.startsWith("WEREWOLF")) {
            String target = context.players().stream()
                    .filter(p -> p.alive() && !p.playerId().equals(context.self().playerId()) && (p.role() == null || !p.role().startsWith("WEREWOLF")))
                    .min(Comparator.comparingInt(AiPlayerInfo::seatNumber))
                    .map(AiPlayerInfo::playerId)
                    .orElse(null);
            return AiDecisionResult.nightAction("WOLF_KILL", target, false, "规则兜底", true);
        }
        if ("SEER".equals(role)) {
            String target = context.players().stream()
                    .filter(p -> p.alive() && !p.playerId().equals(context.self().playerId()))
                    .min(Comparator.comparingInt(AiPlayerInfo::seatNumber))
                    .map(AiPlayerInfo::playerId)
                    .orElse(null);
            return AiDecisionResult.nightAction("SEER_CHECK", target, false, "规则兜底", true);
        }
        if ("WITCH".equals(role)) {
            String wolfTarget = asString(context.extra().get("wolfTarget"));
            if (StringUtils.hasText(wolfTarget)) {
                return AiDecisionResult.nightAction("WITCH_SAVE", wolfTarget, true, "规则兜底", true);
            }
            String target = context.players().stream()
                    .filter(p -> p.alive() && !p.playerId().equals(context.self().playerId()))
                    .max(Comparator.comparingInt(AiPlayerInfo::seatNumber))
                    .map(AiPlayerInfo::playerId)
                    .orElse(null);
            return AiDecisionResult.nightAction("WITCH_POISON", target, false, "规则兜底", true);
        }
        return AiDecisionResult.nightAction("", null, false, "无可用夜晚行动", true);
    }

    private boolean isLegalNightAction(AiGameContext context, String action, String targetId, boolean useHeal) {
        String role = context.self().role();
        if ("WOLF_KILL".equals(action)) {
            return role != null && role.startsWith("WEREWOLF") && isAliveTarget(context, targetId, false);
        }
        if ("SEER_CHECK".equals(action)) {
            return "SEER".equals(role) && isAliveTarget(context, targetId, false);
        }
        if ("WITCH_SAVE".equals(action)) {
            return "WITCH".equals(role) && useHeal;
        }
        if ("WITCH_POISON".equals(action)) {
            return "WITCH".equals(role) && isAliveTarget(context, targetId, false);
        }
        return false;
    }

    private boolean isAliveTarget(AiGameContext context, String targetId, boolean allowSelf) {
        return context.players().stream().anyMatch(p -> p.alive()
                && p.playerId().equals(targetId)
                && (allowSelf || !p.playerId().equals(context.self().playerId())));
    }

    private String playerIdBySeat(AiGameContext context, Integer seat) {
        if (seat == null) {
            return null;
        }
        return context.players().stream()
                .filter(AiPlayerInfo::alive)
                .filter(p -> p.seatNumber() == seat)
                .filter(p -> !p.playerId().equals(context.self().playerId()))
                .map(AiPlayerInfo::playerId)
                .findFirst()
                .orElse(null);
    }

    private int speechLength(AiGameContext context, String displayName) {
        return context.speeches().stream()
                .filter(s -> s.startsWith(displayName + "：") || s.startsWith(displayName + "（AI）："))
                .mapToInt(String::length)
                .sum();
    }

    private List<String> tail(List<String> values, int limit) {
        if (values.size() <= limit) {
            return values;
        }
        return values.subList(values.size() - limit, values.size());
    }

    private long elapsedMs(long startedAt) {
        return Math.max(0, (System.nanoTime() - startedAt) / 1_000_000);
    }

    private Integer asInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private Double asDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text) {
            try {
                return Double.parseDouble(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private List<String> asStringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(item -> item == null ? "" : sanitizeText(item.toString(), 120))
                    .filter(StringUtils::hasText)
                    .toList();
        }
        if (StringUtils.hasText(asString(value))) {
            return List.of(sanitizeText(asString(value), 120));
        }
        return List.of();
    }

    private String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private String normalizeAction(String action) {
        return StringUtils.hasText(action) ? action.trim().toUpperCase(Locale.ROOT) : "";
    }

    private String sanitizeText(String content, int maxLength) {
        if (!StringUtils.hasText(content)) {
            return null;
        }
        String normalized = content.strip();
        int newline = normalized.indexOf('\n');
        if (newline > 0) {
            normalized = normalized.substring(0, newline).trim();
        }
        if (normalized.length() > maxLength) {
            normalized = normalized.substring(0, maxLength);
        }
        return normalized;
    }

    private record DecisionInput(AiGameContext context, Map<String, Object> belief, Map<String, Object> memory) {}

    private record CallResult(Optional<Map<String, Object>> payload,
                              Map<String, Object> rawOutput,
                              AiChatResult response,
                              long latencyMs) {}
}
