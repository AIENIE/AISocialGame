package com.aisocialgame.service.ai.v2;

import com.aisocialgame.engine.v2.GameRuleSet;
import com.aisocialgame.engine.v2.LegalAction;
import com.aisocialgame.engine.v2.RuleSupport;
import com.aisocialgame.engine.v2.turtlesoup.TurtleSoupCaseCatalog;
import com.aisocialgame.engine.v2.undercover.UndercoverWordCatalog;
import com.aisocialgame.model.GameLogEntry;
import com.aisocialgame.model.GamePlayerState;
import com.aisocialgame.model.GameState;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.*;

import static com.aisocialgame.engine.v2.RuleSupport.*;

/** Test-only normalization. Gold expectations never enter an observation or a prompt. */
final class AiRealismScenarioFixtures {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final Set<String> NIGHT_ACTIONS = Set.of("WOLF_KILL", "SEER_CHECK", "WITCH_SAVE", "WITCH_POISON", "GUARD_PROTECT");
    private final Map<String, GameRuleSet> ruleSets;
    private final UndercoverWordCatalog words;
    private final TurtleSoupCaseCatalog soups;

    AiRealismScenarioFixtures(List<GameRuleSet> rules, UndercoverWordCatalog words, TurtleSoupCaseCatalog soups) {
        this.ruleSets = new LinkedHashMap<>();
        rules.forEach(rule -> ruleSets.put(rule.gameId(), rule));
        this.words = words; this.soups = soups;
    }

    List<Scenario> load() throws IOException {
        List<Scenario> scenarios = new ArrayList<>();
        for (String game : List.of("undercover", "werewolf", "turtle_soup")) {
            try (var input = new ClassPathResource("ai-scenarios/" + game + ".json").getInputStream()) {
                Map<String, Object> document = map(JSON.readValue(input, Map.class));
                List<Map<String, Object>> source = maps(document.get("scenarios"));
                if (source.size() != 10) throw new IllegalStateException("Expected exactly ten " + game + " scenarios");
                for (Map<String, Object> raw : source) scenarios.add(normalize(game, raw));
            }
        }
        if (scenarios.stream().map(Scenario::id).distinct().count() != 30) throw new IllegalStateException("Duplicate scenario ids");
        return List.copyOf(scenarios);
    }

    static List<Scenario> select(List<Scenario> all, String selection) {
        if (selection == null || selection.isBlank()) return all;
        List<String> ids = Arrays.stream(selection.split(",", -1)).map(String::strip).toList();
        Set<String> known = new HashSet<>(); all.forEach(s -> known.add(s.id()));
        if (new HashSet<>(ids).size() != ids.size() || !known.containsAll(ids))
            throw new IllegalArgumentException("Unknown, empty or duplicate comparison scenario selection");
        return all.stream().filter(s -> ids.contains(s.id())).toList();
    }

    private Scenario normalize(String game, Map<String, Object> raw) {
        String id = game + ":" + text(raw.getOrDefault("scenarioId", raw.get("id")));
        Map<String, Object> actor = map(raw.get("actor"));
        String actorId = text(actor.getOrDefault("playerId", "blank_last".equals(raw.get("id")) ? "p3" : "p1"));
        String phase = text(raw.getOrDefault("phase", "QUESTIONING"));
        int round = number(raw.get("round"), 1);
        String trait = text(actor.getOrDefault("persona", "温和、有主见，愿意承认误会并照顾同桌玩家"));
        Map<String, Object> persona = Map.of("name", "对照玩家", "trait", trait, "speechStyle", "口语短句、认真听别人说话",
                "strategyStyle", "按可见证据修正判断", "riskPreference", 2, "emotionalRecovery", 2, "sociability", 2);
        Map<String, Object> privateFacts = map(raw.getOrDefault("privateKnowledge", raw.getOrDefault("self", Map.of())));
        Map<String, Object> memory = new LinkedHashMap<>();
        for (String key : List.of("beliefs", "relationships")) {
            if (privateFacts.containsKey(key)) memory.put(key, privateFacts.remove(key));
        }
        if ("undercover".equals(game) && !flag(privateFacts.get("blank"))) {
            words.ownKnowledge(text(privateFacts.get("word"))).ifPresent(word -> privateFacts.put("wordKnowledge", word.visibleData()));
        }
        List<Map<String, Object>> events = new ArrayList<>();
        for (Map<String, Object> event : maps(raw.getOrDefault("visibleEvents", raw.get("events")))) {
            Map<String, Object> visible = new LinkedHashMap<>();
            visible.put("eventId", text(event.get("eventId")));
            visible.put("actorId", text(event.get("actorId")));
            String target = text(event.getOrDefault("targetPlayerId", event.get("targetId")));
            visible.put("targetId", target);
            String type = text(event.getOrDefault("type", "SPEAK"));
            if ("SPEECH".equals(type)) type = "SPEAK";
            visible.put("type", type);
            String message = text(event.get("content"));
            if (message.isEmpty()) message = display(text(event.get("actorId"))) + "投票给" + ("abstain".equals(target) ? "弃票" : display(target)) + "。";
            visible.put("message", message); visible.put("phase", phase); visible.put("round", round);
            events.add(visible);
        }
        // Production observations include actor-authorized private events as well as public events.
        // Keep those IDs addressable for evidence validation, without moving them into legacy public logs.
        for (Map<String, Object> check : maps(privateFacts.get("seerChecks"))) {
            events.add(Map.of("eventId", text(check.get("eventId")), "actorId", actorId,
                    "targetId", text(check.get("targetPlayerId")), "type", "SEER_RESULT", "phase", "NIGHT",
                    "round", number(check.get("round"), round), "message", "本人的查验：" + display(text(check.get("targetPlayerId"))) + "=" + text(check.get("result")),
                    "data", check));
        }
        for (Map<String, Object> council : maps(privateFacts.get("wolfCouncil"))) {
            events.add(Map.of("eventId", text(council.get("eventId")), "actorId", text(council.get("actorId")),
                    "targetId", text(council.get("targetPlayerId")), "type", "WOLF_COUNCIL", "phase", "NIGHT",
                    "round", round, "message", text(council.get("content")), "data", council));
        }
        if ("turtle_soup".equals(game)) {
            TurtleSoupCaseCatalog.SoupCase soup = soups.require(text(raw.get("caseId")));
            events.addFirst(Map.of("eventId", "surface", "actorId", RuleSupport.HOST, "type", "CASE_START", "message", soup.surface(), "phase", phase, "round", round));
        }
        String ownRole = text(actor.get("role"));
        int initialPlayerCount = "HUNTER".equals(ownRole) ? 9 : "IDIOT".equals(ownRole) ? 12 : 6;
        int playerCount = "pressure_blank_final".equals(raw.get("id")) ? 3 : initialPlayerCount;
        Set<String> roleActionTargets = new HashSet<>();
        maps(raw.get("legalActions")).forEach(a -> roleActionTargets.addAll(strings(a.getOrDefault("targets", a.get("targetPlayerIds")))));
        List<Map<String, Object>> players = new ArrayList<>();
        for (int seat = 1; seat <= playerCount; seat++) {
            String playerId = "p" + seat;
            Map<String, Object> player = new LinkedHashMap<>();
            player.put("playerId", playerId); player.put("displayName", display(playerId)); player.put("seatNumber", seat);
            player.put("ai", actorId.equals(playerId)); player.put("alive", true);
            if (actorId.equals(playerId)) {
                if (actor.containsKey("role")) player.put("role", actor.get("role"));
                if ("undercover".equals(game)) {
                    player.put("word", text(privateFacts.get("word")));
                    if (flag(privateFacts.get("blank"))) player.put("role", "BLANK");
                }
            }
            if (strings(privateFacts.get("wolfTeam")).contains(playerId)) player.put("role", "WEREWOLF");
            if (id.endsWith("werewolf_trusted_player_dies") && "p4".equals(playerId)
                    || id.endsWith("werewolf_hunter_wrongly_exiled") && actorId.equals(playerId)
                    || id.endsWith("wrong_vote_regret") && "p2".equals(playerId)) player.put("alive", false);
            if (Set.of("WITCH", "GUARD", "HUNTER").contains(ownRole) && !roleActionTargets.isEmpty()
                    && !actorId.equals(playerId) && !roleActionTargets.contains(playerId)
                    && !playerId.equals(text(privateFacts.get("lastGuardTarget")))) player.put("alive", false);
            if (id.endsWith("wrong_vote_regret") && "p2".equals(playerId)) player.put("role", "CIVILIAN");
            players.add(player);
        }
        Map<String, Object> self = players.stream().filter(p -> actorId.equals(p.get("playerId"))).findFirst().orElseThrow();
        List<LegalAction> legal = capabilities(game, phase, actorId, players, raw);
        Map<String, Object> rules = new LinkedHashMap<>(Map.of("ruleVersion", 2, "playerCount", initialPlayerCount));
        if ("turtle_soup".equals(game)) {
            rules.put("maxQuestions", 12); rules.put("remainingExploration", number(raw.get("remainingExploration"), 8));
        }
        if ("werewolf".equals(game)) {
            rules.put("deathReveal", false);
            String template = "GUARD".equals(ownRole) ? "guard" : "standard";
            rules.put("template", template);
            if (ruleSets.get(game) instanceof com.aisocialgame.engine.v2.werewolf.WerewolfRuleSet wolf) {
                rules.put("roleCounts", wolf.roleCounts(template, initialPlayerCount));
            }
        }
        VisibleObservation base = new VisibleObservation(game, "comparison:" + id, phase, round, actorId, legal.getFirst().type(),
                self, players, rules, events, privateFacts, legal, persona, memory, List.of());
        GameRuleSet adapter = ruleSets.get(game);
        VisibleObservation observation = new VisibleObservation(game, base.instanceId(), phase, round, actorId, base.turnKind(),
                self, players, rules, events, privateFacts, legal, persona, memory, adapter.knowledge(base));
        Map<String, Object> expected = map(raw.get("expectations"));
        if (expected.isEmpty()) expected = new LinkedHashMap<>(Map.of("behavior", strings(raw.get("expected")), "forbidden", strings(raw.get("prohibited"))));
        String baselineAction = "VOTING".equals(phase) ? "VOTE" : "NIGHT".equals(phase) ? "NIGHT_ACTION" : "SPEECH";
        String match = "undercover".equals(game) && Set.of("DESCRIPTION", "VOTING").contains(phase)
                || "werewolf".equals(game) && Set.of("DAY_DISCUSS", "NIGHT").contains(phase) ? "CLOSEST_EXISTING_ACTION" : "APPROXIMATE_UNSUPPORTED_PHASE";
        List<String> limitations = new ArrayList<>();
        limitations.add("Detached synthetic fixture; only authored visible state is supplied. Gold expectations are withheld from both prompts.");
        limitations.add("Legacy and v2 preserve their own production context, prompt, validation and fallback behavior; this is not an isolated prompt A/B test.");
        if (Set.of("WITCH", "GUARD", "HUNTER").contains(ownRole)) limitations.add("Synthetic roster marks seats omitted from the authored skill targets as dead, except self and a guard's explicitly blocked previous target. Hunter uses the supported nine-seat board.");
        if ("IDIOT".equals(ownRole)) limitations.add("The revealed-idiot fixture uses the supported twelve-seat standard board.");
        if ("pressure_blank_final".equals(raw.get("id"))) limitations.add("The fixture lists the three remaining players of an initial six-player game; earlier eliminated-player details were not authored and are omitted.");
        if ("werewolf".equals(game)) limitations.add("Legacy context cannot represent all v2 private council/history/intent fields; missing fields are not invented or inserted into public speech.");
        if (Set.of("GUARD", "HUNTER", "IDIOT").contains(text(self.get("role")))) match = "APPROXIMATE_UNSUPPORTED_ROLE_ACTION";
        if ("turtle_soup".equals(game)) {
            match = "ACTUAL_LEGACY_SCRIPT_NO_MODEL"; baselineAction = "SCRIPT_QUESTION";
            limitations.add("Legacy turtle soup never used an LLM: compare its actual nextAiQuestion script, zero remote calls. The v2 authored story fixes causal gaps, so story-version differences also apply.");
        }
        if (match.startsWith("APPROXIMATE")) limitations.add("Legacy had no equivalent interaction/role action; nearest existing speech or night action is labeled approximate, not an equivalent gameplay comparison.");
        return new Scenario(id, game, text(raw.getOrDefault("caseId", "")), observation, adapter, baselineAction, match, limitations, expected);
    }

    private List<LegalAction> capabilities(String game, String phase, String actorId, List<Map<String, Object>> players, Map<String, Object> raw) {
        List<LegalAction> result = new ArrayList<>();
        if ("werewolf".equals(game)) {
            for (Map<String, Object> action : maps(raw.get("legalActions"))) {
                String type = text(action.get("type"));
                List<String> targets = strings(action.getOrDefault("targets", action.get("targetPlayerIds")));
                result.add(NIGHT_ACTIONS.contains(type) ? LegalAction.night(type, type, targets)
                        : new LegalAction(type, type, action.containsKey("nightAction") ? text(action.get("nightAction")) : null,
                        targets, number(action.get("maxLength"), "SKIP".equals(type) ? 0 : 120)));
            }
        } else if ("undercover".equals(game)) {
            List<String> others = players.stream().filter(p -> !actorId.equals(p.get("playerId")) && flag(p.get("alive"))).map(p -> text(p.get("playerId"))).toList();
            switch (phase) {
                case "CHALLENGE" -> { result.add(LegalAction.target("ASK_PLAYER", "质疑", others, 60)); result.add(LegalAction.simple("SKIP", "跳过")); }
                case "RESPONSE" -> result.add(LegalAction.target("ANSWER_PLAYER", "回应", List.of("p2"), 60));
                case "VOTING" -> { result.add(LegalAction.target("VOTE", "投票", others, 0)); result.add(LegalAction.simple("SKIP", "弃票")); }
                case "TIE_DEFENSE" -> { result.add(LegalAction.text("SPEAK", "辩解", 60)); result.add(LegalAction.simple("SKIP", "跳过")); }
                default -> result.add(LegalAction.text("SPEAK", "描述", 60));
            }
        } else {
            result.add(LegalAction.text("DISCUSS", "讨论", 180));
            if (number(raw.get("remainingExploration"), 8) > 2 && !"useful_silence".equals(raw.get("id"))) result.add(LegalAction.text("ASK_QUESTION", "提问", 120));
            result.add(LegalAction.simple("PASS", "让出发言"));
        }
        return List.copyOf(result);
    }

    GameState legacyState(Scenario scenario) {
        VisibleObservation observation = scenario.observation();
        GameState state = new GameState(observation.instanceId(), scenario.gameId(), observation.phase());
        state.setRoundNumber(observation.round());
        List<GamePlayerState> players = new ArrayList<>();
        for (Map<String, Object> visible : observation.players()) {
            String id = text(visible.get("playerId"));
            GamePlayerState player = new GamePlayerState(id, text(visible.get("displayName")), number(visible.get("seatNumber"), 1),
                    id.equals(observation.actorId()), id.equals(observation.actorId()) ? scenario.id() : null, "");
            player.setRole(visible.containsKey("role") ? text(visible.get("role")) : null);
            player.setWord(visible.containsKey("word") ? text(visible.get("word")) : null);
            player.setAlive(flag(visible.get("alive"))); player.setConnectionStatus("ONLINE"); players.add(player);
        }
        state.setPlayers(players); state.setLogs(new ArrayList<>()); state.setData(new LinkedHashMap<>());
        Set<String> privateEventIds = new HashSet<>();
        for (String key : List.of("seerChecks", "wolfCouncil")) maps(observation.privateFacts().get(key)).forEach(e -> privateEventIds.add(text(e.get("eventId"))));
        for (Map<String, Object> event : observation.events()) {
            if (privateEventIds.contains(text(event.get("eventId")))) continue;
            String type = text(event.get("type"));
            String oldType = Set.of("SPEAK", "ASK_PLAYER", "ANSWER_PLAYER", "CLAIM").contains(type) ? "speak" : type.contains("VOTE") ? "vote" : "event";
            String speaker = text(event.get("actorId"));
            String message = (speaker.startsWith("p") ? display(speaker) + "：" : "") + text(event.get("message"));
            GameLogEntry log = new GameLogEntry(oldType, message);
            log.setActorId(speaker); log.setTargetId(text(event.get("targetId"))); log.setMetadata(Map.of("eventId", event.get("eventId")));
            state.getLogs().add(log);
        }
        if (observation.privateFacts().containsKey("seerChecks")) state.getData().put("seerResults", Map.of(observation.actorId(), observation.privateFacts().get("seerChecks")));
        if (observation.privateFacts().containsKey("wolfTarget")) state.getData().put("wolfTarget", observation.privateFacts().get("wolfTarget"));
        state.getData().put("caseId", scenario.caseId());
        return state;
    }

    /** Export-only list for a separately budgeted future host test; no automatic remote execution. */
    List<Map<String, Object>> turtleHostFixtures() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (TurtleSoupCaseCatalog.SoupCase soup : soups.cases()) {
            for (var fixture : soup.questionFixtures()) result.add(Map.of("caseId", soup.id(), "caseVersion", soup.version(), "type", "ASK_QUESTION", "content", fixture.question(), "expectedVerdict", fixture.verdict(), "expectedFactIds", fixture.factIds()));
            for (var fixture : soup.solutionFixtures()) result.add(Map.of("caseId", soup.id(), "caseVersion", soup.version(), "type", "SUBMIT_SOLUTION", "content", fixture.solution(), "expectedSolved", fixture.solved(), "reason", fixture.reason()));
        }
        return List.copyOf(result);
    }

    /** Host-only input for reuse by a separately budgeted semantic test. Gold verdicts stay outside it. */
    VisibleObservation turtleHostObservation(Map<String, Object> fixture) {
        var soup = soups.require(text(fixture.get("caseId")));
        String type = text(fixture.get("type"));
        if (!Set.of("ASK_QUESTION", "SUBMIT_SOLUTION").contains(type)) throw new IllegalArgumentException("Unsupported host fixture action");
        Map<String, Object> pending = Map.of("requestId", "semantic-fixture-request", "type", type,
                "actorId", "p1", "displayName", "一号", "content", text(fixture.get("content")), "aiGenerated", false, "finalAttempt", false);
        return new VisibleObservation("turtle_soup", "semantic-fixture:" + soup.id(), "QUESTIONING", 1, HOST,
                "ASK_QUESTION".equals(type) ? "HOST_ANSWER" : "HOST_SOLUTION",
                Map.of("playerId", HOST, "displayName", "主持人", "role", "HOST"),
                List.of(Map.of("playerId", "p1", "displayName", "一号", "seatNumber", 1, "alive", true)),
                Map.of("ruleVersion", 2), List.of(Map.of("eventId", "surface", "type", "CASE_START", "message", soup.surface())),
                Map.of("truth", soup.hostTruth(), "pendingHost", pending, "previousPropositions", List.of()),
                List.of(LegalAction.simple("HOST_VERDICT", "裁决当前请求")), Map.of("name", "主持人"), Map.of(), List.of());
    }

    private static String display(String playerId) {
        if (playerId == null || !playerId.matches("p(?:[1-9]|1[0-2])")) return text(playerId);
        return List.of("一号", "二号", "三号", "四号", "五号", "六号", "七号", "八号", "九号", "十号", "十一号", "十二号").get(Integer.parseInt(playerId.substring(1)) - 1);
    }

    record Scenario(String id, String gameId, String caseId, VisibleObservation observation, GameAiAdapter adapter,
                    String baselineAction, String baselineMatch, List<String> limitations, Map<String, Object> expectations) {}
}
