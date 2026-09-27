package com.aisocialgame.service.ai.v2;

import com.aisocialgame.engine.v2.*;
import com.aisocialgame.model.GameState;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
import static com.aisocialgame.engine.v2.RuleSupport.*;

/** Bounded, local action contracts. Only server receipts can fulfill them. */
public final class AiCommitments {
    private AiCommitments() {}
    public enum Status { UNVERIFIED, ACTIVE, FULFILLED, WITHDRAWN, NOT_FULFILLED, EXPIRED }
    private static final Set<String> FIELDS = Set.of("text", "content", "hypothesis", "eventId", "evidenceEventIds", "sourceQuote", "action", "roundOffset", "conditional", "confidence");
    private static final Pattern CONDITIONAL = Pattern.compile("如果|若|除非|假如|要是|只要|的话|视情况|可能|也许|if\\b|unless\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern WITHDRAW = Pattern.compile("撤回|取消|不再承诺|收回|withdraw|retract", Pattern.CASE_INSENSITIVE);

    public static AiMemoryEntries.Parsed proposals(Object value, Set<String> visible, int round) {
        if (value == null) return new AiMemoryEntries.Parsed(List.of(), List.of());
        if (!(value instanceof List<?> list)) return new AiMemoryEntries.Parsed(List.of(), List.of("INVALID_COMMITMENT_FIELDS"));
        List<Map<String, Object>> result = new ArrayList<>(); Set<String> errors = new LinkedHashSet<>();
        for (Object item : list) {
            if (!(item instanceof String) && !(item instanceof Map<?, ?>)) { errors.add("INVALID_COMMITMENT_FIELDS"); continue; }
            Map<String, Object> input = item instanceof String s ? Map.of("text", s) : map(item);
            if (!FIELDS.containsAll(input.keySet())) { errors.add("INVALID_COMMITMENT_FIELDS"); continue; }
            Map<String, Object> basic = new LinkedHashMap<>(input);
            for (String key : List.of("sourceQuote", "action", "roundOffset", "conditional")) basic.remove(key);
            var parsed = AiMemoryEntries.proposals(List.of(basic), false, visible, round);
            if (!parsed.errors().isEmpty()) { errors.addAll(parsed.errors()); continue; }
            if (input.containsKey("sourceQuote") && (!(input.get("sourceQuote") instanceof String q) || q.isBlank() || q.codePointCount(0, q.length()) > 160)
                    || input.containsKey("roundOffset") && (!(input.get("roundOffset") instanceof Number n) || !Double.isFinite(n.doubleValue()) || n.doubleValue() != Math.rint(n.doubleValue()) || Math.abs(n.doubleValue()) > 10000)
                    || input.containsKey("conditional") && !(input.get("conditional") instanceof Boolean)) {
                errors.add("INVALID_COMMITMENT_FIELDS"); continue;
            }
            Map<String, Object> action = map(input.get("action"));
            if (input.containsKey("action") && (!(input.get("action") instanceof Map<?, ?>) || !Set.of("kind", "targetPlayerId", "ballot").containsAll(action.keySet())
                    || !(action.get("kind") instanceof String k) || k.isBlank()
                    || action.values().stream().anyMatch(v -> !(v instanceof String))
                    || action.containsKey("ballot") && !Set.of("NORMAL", "RUNOFF").contains(action.get("ballot")))) {
                errors.add("INVALID_COMMITMENT_FIELDS"); continue;
            }
            Map<String, Object> entry = new LinkedHashMap<>(parsed.entries().getFirst());
            entry.put("sourceQuote", text(input.get("sourceQuote"))); entry.put("action", action);
            entry.put("roundOffset", number(input.get("roundOffset"), 0)); entry.put("conditional", Boolean.TRUE.equals(input.get("conditional")));
            if (result.size() < 8) result.add(entry);
        }
        return new AiMemoryEntries.Parsed(result, List.copyOf(errors));
    }
    public static List<String> validateWithdrawals(Object value, Set<String> visible) {
        if (value == null) return List.of();
        if (!(value instanceof List<?> list)) return List.of("INVALID_COMMITMENT_WITHDRAWAL");
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m) || !Set.of("id", "sourceQuote", "eventId").containsAll(m.keySet())
                    || !(m.get("id") instanceof String id) || id.isBlank()
                    || !(m.get("sourceQuote") instanceof String q) || q.isBlank() || q.codePointCount(0, q.length()) > 160
                    || m.containsKey("eventId") && (!(m.get("eventId") instanceof String e) || !visible.contains(e))) return List.of("INVALID_COMMITMENT_WITHDRAWAL");
        }
        return List.of();
    }
    /** Legacy IDs are deterministic on pure reads, and become persisted only on the next write. */
    public static List<Map<String, Object>> stored(Object value, int version, String instance, String actor) {
        if (!(value instanceof List<?> list)) return new ArrayList<>();
        List<Map<String, Object>> result = new ArrayList<>(); int index = 0;
        for (Object item : list) {
            Map<String, Object> entry;
            if (version >= 3 && item instanceof Map<?, ?> m && m.get("id") instanceof String && validStatus(m.get("status"))) {
                entry = new LinkedHashMap<>(map(item));
            } else {
                var old = AiMemoryEntries.stored(Collections.singletonList(item), false, version >= 2);
                if (old.isEmpty()) continue;
                entry = new LinkedHashMap<>(old.getFirst()); entry.put("status", Status.UNVERIFIED.name());
                entry.put("id", UUID.nameUUIDFromBytes((instance + ":" + actor + ":legacy:" + index + ":" + entry.get("text")).getBytes(StandardCharsets.UTF_8)).toString());
                entry.put("sourceQuote", ""); entry.put("action", Map.of()); entry.put("opportunity", Map.of());
            }
            result.add(entry); index++;
        }
        // Persisted v3 is produced bounded. For legacy overflow keep the newest records.
        return new ArrayList<>(result.subList(Math.max(0, result.size() - 16), result.size()));
    }
    private static boolean validStatus(Object value) {
        return Arrays.stream(Status.values()).anyMatch(s -> s.name().equals(value));
    }
    public static List<String> update(GameState s, GameRuleSet rules, String actor, Map<String, Object> memory,
                                      AiTurnDecision decision, VisibleObservation o, int newEventStart) {
        List<Map<String, Object>> entries = stored(memory.get("commitments"), 3, o.instanceId(), actor);
        List<Map<String, Object>> sources = new ArrayList<>(o.events());
        List<Map<String, Object>> allEvents = maps(s.getData().get("events"));
        sources.addAll(allEvents.subList(Math.min(newEventStart, allEvents.size()), allEvents.size()).stream()
                .filter(e -> "PUBLIC".equals(e.get("visibility")) || "PRIVATE".equals(e.get("visibility")) && strings(e.get("visibleTo")).contains(actor)).toList());
        List<String> flags = new ArrayList<>();
        for (Map<String, Object> withdrawal : maps(decision.memoryUpdates().get("commitmentWithdrawals")).stream().limit(8).toList()) {
            Map<String, Object> source = source(sources, actor, text(withdrawal.get("sourceQuote")), text(withdrawal.get("eventId")), List.of());
            Map<String, Object> entry = entries.stream().filter(e -> Objects.equals(e.get("id"), withdrawal.get("id"))).findFirst().orElse(null);
            if (entry == null || !"ACTIVE".equals(entry.get("status")) || source.isEmpty()
                    || !WITHDRAW.matcher(text(withdrawal.get("sourceQuote"))).find()
                    || text(withdrawal.get("sourceQuote")).matches("(?s).*(?:不撤回|不取消|不收回|如果|要是|的话|吗|？|\\?).*")
                    || CONDITIONAL.matcher(AiGrounding.content(source)).find()
                    || !withdrawalMatches(s, entry, text(withdrawal.get("sourceQuote")), entries)
                    || rules == null || rules.commitmentExpiry(s, map(entry.get("opportunity")), map(entry.get("action"))) != null
                    || !afterSource(allEvents, entry, source)) {
                flags.add("COMMITMENT_WITHDRAWAL_IGNORED"); continue;
            }
            resolve(entry, Status.WITHDRAWN, "EXPLICIT_WITHDRAWAL", "PLAYER_STATEMENT", List.of(text(source.get("eventId"))), s.getRoundNumber(), Map.of());
        }
        Set<String> visible = new HashSet<>(); o.events().forEach(e -> visible.add(text(e.get("eventId"))));
        var parsed = proposals(decision.memoryUpdates().get("commitments"), visible, o.round());
        if (!parsed.errors().isEmpty()) throw new IllegalArgumentException(String.join(",", parsed.errors()));
        for (Map<String, Object> proposal : parsed.entries()) {
            Map<String, Object> entry = new LinkedHashMap<>(proposal);
            entry.put("id", UUID.randomUUID().toString()); entry.put("status", Status.UNVERIFIED.name()); entry.put("opportunity", Map.of());
            String quote = text(entry.get("sourceQuote"));
            Map<String, Object> source = source(sources, actor, quote, "", strings(entry.get("evidenceEventIds")));
            int offset = number(entry.remove("roundOffset"), 0);
            Map<String, Object> action = map(entry.get("action"));
            if (source.isEmpty()) entry.put("sourceQuote", "");
            if (!source.isEmpty()) {
                entry.put("sourceEventId", source.get("eventId"));
                LinkedHashSet<String> ids = new LinkedHashSet<>(strings(entry.get("evidenceEventIds"))); ids.add(text(source.get("eventId")));
                List<String> bounded = new ArrayList<>(ids); entry.put("evidenceEventIds", bounded.subList(Math.max(0, bounded.size() - 6), bounded.size()));
            }
            if (rules != null && !source.isEmpty() && offset >= 0 && offset <= 1 && !Boolean.TRUE.equals(entry.get("conditional"))
                    && !CONDITIONAL.matcher(AiGrounding.content(source) + quote + entry.get("text")).find() && explicitAction(s, actor, quote, action)
                    && relativeScopeMatches(quote, action, offset)) {
                // A historical statement's relative round is anchored to that statement, never to today's model output.
                int sourceRound = number(source.get("round"), o.round());
                var opportunity = rules.commitmentOpportunity(s, actor, action, sourceRound + offset);
                if (opportunity != null) {
                    entry.put("opportunity", opportunity.value()); entry.put("status", Status.ACTIVE.name());
                }
            }
            boolean duplicateSource = !source.isEmpty() && entries.stream().anyMatch(e -> Objects.equals(e.get("sourceEventId"), entry.get("sourceEventId"))
                    && Objects.equals(e.get("action"), action) && Objects.equals(e.get("text"), entry.get("text")));
            if (duplicateSource) continue;
            if (entries.size() >= 16) {
                int evict = -1;
                for (int i = 0; i < entries.size(); i++) if (!"ACTIVE".equals(entries.get(i).get("status"))) { evict = i; break; }
                if (evict < 0) { flags.add("COMMITMENT_CAPACITY_REACHED"); continue; }
                entries.remove(evict);
            }
            entries.add(entry);
        }
        memory.put("commitments", entries); memory.put("commitmentQualityFlags", flags.stream().distinct().toList());
        return flags;
    }
    private static boolean afterSource(List<Map<String, Object>> events, Map<String, Object> entry, Map<String, Object> source) {
        int first = -1, second = -1;
        for (int i = 0; i < events.size(); i++) {
            if (Objects.equals(events.get(i).get("eventId"), entry.get("sourceEventId"))) first = i;
            if (Objects.equals(events.get(i).get("eventId"), source.get("eventId"))) second = i;
        }
        return first >= 0 && second > first;
    }
    private static Map<String, Object> source(List<Map<String, Object>> events, String actor, String quote, String requiredId, List<String> ids) {
        if (quote.isBlank()) return Map.of();
        return events.stream().filter(AiSpeechEvents::isSpeech).filter(e -> actor.equals(e.get("actorId")))
                .filter(e -> requiredId.isBlank() || requiredId.equals(e.get("eventId")))
                .filter(e -> ids.isEmpty() || ids.contains(text(e.get("eventId"))))
                .filter(e -> AiGrounding.content(e).contains(quote)).reduce((a, b) -> b).orElse(Map.of());
    }
    /** Conservative explicit forms only. Unsupported natural language remains UNVERIFIED, never invalidates speech. */
    private static boolean explicitAction(GameState s, String actor, String quote, Map<String, Object> action) {
        if (!quote.matches("(?s).*(?:我(?:会|要|承诺|本轮|这轮|下轮|下一轮|今晚|本夜|下一夜|今夜)|(?:今晚|本夜|本轮|下轮|下一轮|下一夜)我).*")
                || quote.matches("(?s).*(?:不投|不会|不承诺|不查|不守|不救|不毒|刚才|已经|之前|说过|吗|？|\\?).*")
                || WITHDRAW.matcher(quote).find()) return false;
        String kind = text(action.get("kind"));
        String verb = actionVerb(kind);
        if (!Pattern.compile(verb, Pattern.CASE_INSENSITIVE).matcher(quote).find()) return false;
        String target = text(action.get("targetPlayerId"));
        if (Set.of("ABSTAIN", "NIGHT_SKIP").contains(kind)) return target.isBlank();
        return targetFollowsAction(s, quote, action);
    }
    private static String actionVerb(String kind) {
        return switch (kind) {
            case "VOTE" -> "投给|投票给|投"; case "ABSTAIN" -> "弃票|abstain";
            case "WOLF_KILL" -> "刀|击杀|杀"; case "SEER_CHECK" -> "查验|验|查";
            case "GUARD_PROTECT" -> "守护|守"; case "WITCH_SAVE" -> "救|解药";
            case "WITCH_POISON" -> "毒|投毒"; case "NIGHT_SKIP" -> "跳过夜间行动|今晚不行动|本夜不行动|空刀|留药|放弃查验|放弃守护";
            default -> "(?!)";
        };
    }
    private static boolean targetFollowsAction(GameState s, String quote, Map<String, Object> action) {
        String target = text(action.get("targetPlayerId"));
        return s.getPlayers().stream().filter(p -> p.getPlayerId().equals(target)).anyMatch(p -> {
            List<String> references = new ArrayList<>(List.of(Pattern.quote(target) + "(?![A-Za-z0-9_-])", p.getSeatNumber() + "号"));
            if (p.getDisplayName() != null && !p.getDisplayName().isBlank()) references.add(Pattern.quote(p.getDisplayName()) + "(?![0-9])");
            String expression = "(?:" + actionVerb(text(action.get("kind"))) + ")(?:给|玩家|一下|选择| )*(?:" + String.join("|", references) + ")";
            return Pattern.compile(expression).matcher(quote).find();
        });
    }
    private static boolean withdrawalMatches(GameState s, Map<String, Object> entry, String quote, List<Map<String, Object>> entries) {
        if (quote.contains(text(entry.get("id")))) return true;
        Map<String, Object> action = map(entry.get("action")); String kind = text(action.get("kind"));
        if (Set.of("ABSTAIN", "NIGHT_SKIP").contains(kind)) return Pattern.compile(actionVerb(kind)).matcher(quote).find();
        if (targetFollowsAction(s, quote, action)) return true;
        // A bare withdrawal is unambiguous only with a single active contract and no different action named.
        return entries.stream().filter(e -> "ACTIVE".equals(e.get("status"))).count() == 1
                && quote.matches("我(?:撤回|取消|收回)(?:之前的|刚才的|这个|这项)?承诺[。！]?");
    }
    private static boolean relativeScopeMatches(String quote, Map<String, Object> action, int offset) {
        boolean next = quote.matches("(?s).*(?:下轮|下一轮|下一夜|下个夜晚).*");
        if (next && offset != 1) return false;
        if (!next && quote.matches("(?s).*(?:本轮|这轮|本夜).*") && offset != 0) return false;
        if ("VOTE".equals(action.get("kind")) || "ABSTAIN".equals(action.get("kind"))) {
            if (quote.contains("复投") != "RUNOFF".equals(action.get("ballot"))) return false;
        }
        return true;
    }
    public static void submitted(GameState s, CommitmentRules.Receipt receipt, String origin, int newEventStart) {
        if (receipt == null) return;
        mutate(s, (actor, entry) -> {
            if (!actor.equals(receipt.opportunity().actorId()) || !receipt.opportunity().id().equals(map(entry.get("opportunity")).get("id"))) return;
            Map<String, Object> action = map(entry.get("action"));
            boolean match = receipt.kind().equals(action.get("kind")) && receipt.targetPlayerId().equals(text(action.get("targetPlayerId")));
            List<Map<String, Object>> events = maps(s.getData().get("events"));
            List<String> ids = events.subList(Math.min(newEventStart, events.size()), events.size()).stream()
                    .filter(e -> actor.equals(e.get("actorId"))).map(e -> text(e.get("eventId"))).limit(6).toList();
            resolve(entry, match ? Status.FULFILLED : Status.NOT_FULFILLED, match ? "MATCHING_ACTION" : "DIFFERENT_ACTION", origin, ids,
                    receipt.opportunity().round(), Map.of("kind", receipt.kind(), "targetPlayerId", receipt.targetPlayerId()));
        });
    }
    public static void reconcile(GameState s, GameRuleSet rules, String origin) {
        mutate(s, (actor, entry) -> {
            String reason = rules.commitmentExpiry(s, map(entry.get("opportunity")), map(entry.get("action")));
            if (reason != null) {
                resolve(entry, Status.EXPIRED, reason, origin, List.of(), s.getRoundNumber(), Map.of());
                Map<String, Object> resolution = map(entry.get("resolution"));
                resolution.put("stateBasis", Map.of("round", s.getRoundNumber(), "phase", s.getPhase(),
                        "phaseSerial", number(s.getData().get("phaseSerial"), 0), "opportunityId", text(map(entry.get("opportunity")).get("id"))));
                entry.put("resolution", resolution);
            }
        });
    }
    private static void mutate(GameState s, java.util.function.BiConsumer<String, Map<String, Object>> update) {
        Map<String, Object> all = map(s.getData().get("aiMemoriesV2"));
        for (String actor : all.keySet()) {
            Map<String, Object> memory = map(all.get(actor));
            // Do not rewrite legacy rooms on ticks or infer past outcomes during migration.
            if (number(memory.get("formatVersion"), 1) < 3) continue;
            List<Map<String, Object>> entries = stored(memory.get("commitments"), 3, text(s.getData().get("archiveId")), actor);
            for (Map<String, Object> entry : entries) if ("ACTIVE".equals(entry.get("status"))) update.accept(actor, entry);
            memory.put("commitments", entries); all.put(actor, memory);
        }
        if (!all.isEmpty()) s.getData().put("aiMemoriesV2", all);
    }
    private static void resolve(Map<String, Object> entry, Status status, String reason, String origin, List<String> evidence, int round, Map<String, Object> action) {
        entry.put("status", status.name());
        entry.put("resolution", Map.of("reason", reason, "origin", origin, "evidenceEventIds", evidence, "round", round, "actualAction", action));
    }
}
