package com.aisocialgame.service.ai.v2;

import com.aisocialgame.engine.v2.turtlesoup.*;
import com.aisocialgame.engine.v2.undercover.*;
import com.aisocialgame.engine.v2.werewolf.*;
import com.aisocialgame.engine.v2.RuleSupport;
import com.aisocialgame.model.PersonaPresets;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AiPromptProjectionTest {
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    final UndercoverWordCatalog words = new UndercoverWordCatalog();
    final TurtleSoupCaseCatalog soups = new TurtleSoupCaseCatalog(json);
    final TurtleSoupRuleSet soupRules = new TurtleSoupRuleSet(soups);
    final AiRealismScenarioFixtures fixtures = new AiRealismScenarioFixtures(List.of(new UndercoverRuleSet(words), new WerewolfRuleSet(), soupRules), words, soups);

    @Test void otherGamesAndPresetsRetainExactDataWhileLongHistoryShrinks() throws Exception {
        List<Map<String, Object>> samples = new ArrayList<>();
        for (var scenario : fixtures.load()) for (var persona : PersonaPresets.all()) for (boolean longHistory : List.of(false, true)) {
            if ("undercover".equals(scenario.gameId())) continue;
            var base = scenario.observation();
            List<Map<String, Object>> events = new ArrayList<>(base.events());
            if (longHistory) for (int i = 0; i < 48; i++) events.add(Map.of("eventId", "long-" + i, "actorId", base.actorId(),
                    "round", base.round(), "type", "SPEECH", "visibility", "PUBLIC", "message", "唯一原话" + i + "：我们要核对不同线索的适用范围，不把玩家的理由或声明当成已经发生的系统裁决。".repeat(3), "data", Map.of()));
            var o = new VisibleObservation(base.gameId(), base.instanceId(), base.phase(), base.round(), base.actorId(), base.turnKind(), base.self(), base.players(), base.rules(),
                    events, base.privateFacts(), base.legalActions(), json.convertValue(persona, Map.class), base.memory(), base.knowledge());
            String before = json.writeValueAsString(o);
            Map<String, Object> projected = AiPromptProjection.project(scenario.adapter(), o);
            JsonNode root = json.valueToTree(projected), restored = resolve(root.path("observation"), root, 0), original = json.valueToTree(o);
            for (String field : List.of("events", "privateFacts", "legalActions", "persona", "players", "self", "knowledge")) assertEquals(original.path(field), restored.path(field), scenario.id() + "/" + field);
            original.path("memory").fields().forEachRemaining(e -> assertEquals(e.getValue(), restored.path("memory").get(e.getKey())));
            assertEquals(before, json.writeValueAsString(o), "projection is detached and pure");
            var old = Map.of("instruction", scenario.adapter().instruction(o), "observation", o, "informationSources", AiInformationSources.project(scenario.adapter(), o), "continuity", AiGrounding.context(o));
            int oldBytes = json.writeValueAsBytes(old).length, newBytes = json.writeValueAsBytes(projected).length;
            if (longHistory) assertTrue(newBytes < oldBytes, scenario.id() + ": " + oldBytes + " -> " + newBytes);
            samples.add(Map.of("scenario", scenario.id(), "persona", persona.getId(), "longHistory", longHistory, "oldInputBytes", oldBytes, "newInputBytes", newBytes));
            JsonNode resolvedSources = resolve(root.path("informationSources"), root, 0);
            assertEquals(json.valueToTree(AiInformationSources.project(scenario.adapter(), o)), resolvedSources);
        }
        Files.createDirectories(Path.of("target"));
        json.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/m1-r4-input-comparison.json").toFile(), Map.of("source", "offline; no model calls; UTF-8 user payload bytes only", "samples", samples));
    }

    @Test void hostTruthIsUnchangedAndNoPlayerExamplesOrMemoryAreIntroduced() {
        for (var f : fixtures.turtleHostFixtures()) {
            var o = fixtures.turtleHostObservation(f);
            JsonNode projected = json.valueToTree(AiPromptProjection.project(soupRules, o));
            assertEquals(json.valueToTree(o.privateFacts()), resolve(projected.path("observation").path("privateFacts"), projected, 0));
            assertFalse(projected.has("outputExamples")); assertFalse(projected.has("continuity")); assertFalse(projected.has("conversation"));
        }
    }

    @Test void repeatedEventContentBecomesAResolvableReferenceWithoutChangingHistoricalQuotes() {
        var adapter = new WerewolfRuleSet();
        var o = new VisibleObservation("werewolf", "i", "DAY_DISCUSS", 1, "p", "SPEAK", Map.of(), List.of(), Map.of(),
                List.of(Map.of("eventId", "e", "actorId", "p", "type", "SPEAK", "message", "逐字保留的引用", "data", Map.of("content", "逐字保留的引用"))),
                Map.of(), List.of(), Map.of(), Map.of(), List.of());
        JsonNode projected = json.valueToTree(AiPromptProjection.project(adapter, o));
        assertTrue(projected.at("/observation/events/0/data/content").has("$ref"));
        assertEquals(json.valueToTree(o.events()), resolve(projected.at("/observation/events"), projected, 0));
        assertEquals("逐字保留的引用", resolve(projected.at("/informationSources/events/0/value/statement"), projected, 0).asText());
    }

    @Test void undercoverUsesOnlyItsOwnWordPublicRecordsAndShortPersonaAcrossAllFixtures() throws Exception {
        for (var scenario : fixtures.load()) {
            if (!"undercover".equals(scenario.gameId())) continue;
            var base = scenario.observation();
            for (var persona : PersonaPresets.all()) {
                Map<String, Object> profile = json.convertValue(persona, Map.class);
                profile.put("difficulty", AiRoundReflection.difficulty(persona.getDifficultyLevel()));
                Map<String, Object> privateFacts = new LinkedHashMap<>(base.privateFacts());
                privateFacts.put("wordKnowledge", Map.of("definition", "DEFINITION_MUST_NOT_BE_SENT", "attributes", List.of("ATTRIBUTE_MUST_NOT_BE_SENT"),
                        "scenes", List.of("SCENE_MUST_NOT_BE_SENT"), "forbiddenTerms", List.of("ALIAS_MUST_NOT_BE_SENT")));
                var o = new VisibleObservation(base.gameId(), base.instanceId(), base.phase(), base.round(), base.actorId(), base.turnKind(),
                        base.self(), base.players(), Map.of("wordPack", "PACK_MUST_NOT_BE_SENT", "wordPairId", "PAIR_MUST_NOT_BE_SENT", "knowledgeVersion", "VERSION_MUST_NOT_BE_SENT"),
                        base.events(), privateFacts, base.legalActions(), profile, Map.of("approvedExperience", "EXPERIENCE_MUST_NOT_BE_SENT", "beliefs", Map.of("p2", "BELIEF_MUST_NOT_BE_SENT")),
                        List.of("KNOWLEDGE_MUST_NOT_BE_SENT"));
                String before = json.writeValueAsString(o);
                JsonNode projected = json.valueToTree(AiPromptProjection.project(scenario.adapter(), o));
                assertEquals(AiPromptProjection.UNDERCOVER_VERSION, projected.path("inputFormatVersion").asInt());
                assertFalse(projected.toString().contains("MUST_NOT_BE_SENT"), scenario.id());
                for (String absent : List.of("observation", "informationSources", "continuity", "referenceFormat", "conversation", "outputExamples", "memory", "knowledge", "privateFacts"))
                    assertFalse(projected.has(absent), absent);
                assertFalse(projected.toString().contains("$ref"));
                assertEquals(persona.getSpeechStyle(), projected.at("/persona/speechStyle").asText());
                assertEquals(persona.getStrategyStyle(), projected.at("/persona/strategyStyle").asText());
                assertEquals(4, projected.path("persona").size());
                assertEquals(AiRoundReflection.difficulty(persona.getDifficultyLevel()).get("guide"), projected.at("/persona/difficulty").asText());
                assertEquals(o.actorId(), projected.at("/self/playerId").asText());
                if (RuleSupport.flag(o.privateFacts().get("blank"))) {
                    assertTrue(projected.at("/self/blank").asBoolean()); assertFalse(projected.path("self").has("word"));
                } else assertEquals(o.privateFacts().get("word"), projected.at("/self/word").asText());
                projected.path("players").forEach(player -> { assertFalse(player.has("word")); if (player.path("alive").asBoolean()) assertFalse(player.has("role")); });
                assertEquals(before, json.writeValueAsString(o), "projection must not mutate a frozen observation");
            }
        }
    }

    @Test void undercoverHistoryRetainsEveryMeaningfulRecordOnceAndKeepsRunoffSeparate() {
        var adapter = new UndercoverRuleSet(words);
        List<Map<String, Object>> events = new ArrayList<>();
        events.add(Map.of("eventId", "assigned", "type", "WORD_ASSIGNED", "data", Map.of("word", "唯一秘密词")));
        events.add(Map.of("eventId", "start", "type", "ROUND_START", "message", "不要发送的流程通知"));
        for (int i = 0; i < 65; i++) events.add(Map.of("eventId", "speech-" + i, "type", "SPEAK", "actorId", "p1", "round", i / 6 + 1,
                "message", "一号：原始描述" + i, "data", Map.of("content", "原始描述" + i)));
        events.add(Map.of("eventId", "cast", "type", "VOTE_CAST", "actorId", "p1"));
        events.add(Map.of("eventId", "private-vote", "type", "VOTE_TARGET", "actorId", "p0", "targetId", "p1"));
        events.add(Map.of("eventId", "reveal", "type", "VOTE_REVEAL", "round", 11, "data", Map.of("votes", Map.of("p0", "p1", "p1", "abstain"), "tally", Map.of("p1", 1))));
        events.add(Map.of("eventId", "tie", "type", "TIE", "round", 11, "data", Map.of("candidates", List.of("p1", "p2"))));
        events.add(Map.of("eventId", "extra", "type", "SPEAK", "phase", "TIE_DEFENSE", "round", 11, "actorId", "p1", "data", Map.of("content", "补充描述原文")));
        events.add(Map.of("eventId", "skip", "type", "SKIP", "phase", "TIE_DEFENSE", "round", 11, "actorId", "p2", "data", Map.of("reason", "REQUEST_FAILED")));
        events.add(Map.of("eventId", "timeout", "type", "TIMEOUT", "phase", "DESCRIPTION", "round", 11, "actorId", "p3", "data", Map.of("action", "SPEAK", "reason", "TIMEOUT")));
        events.add(Map.of("eventId", "vote-timeout", "type", "TIMEOUT", "phase", "RUNOFF", "round", 11, "actorId", "p3", "data", Map.of("action", "VOTE")));
        events.add(Map.of("eventId", "runoff", "type", "VOTE_REVEAL", "phase", "RUNOFF", "round", 11, "data", Map.of("votes", Map.of("p0", "p2", "p3", "abstain"))));
        events.add(Map.of("eventId", "out", "type", "ELIMINATED", "round", 11, "actorId", "p2", "data", Map.of("role", "CIVILIAN")));
        events.add(Map.of("eventId", "none", "type", "NO_ELIMINATION", "round", 12, "data", Map.of("consecutiveRounds", 1)));
        var o = new VisibleObservation("undercover", "internal-instance", "RUNOFF", 13, "p0", "VOTE", Map.of("word", "唯一秘密词"), List.of(), Map.of(),
                events, Map.of("word", "唯一秘密词"), List.of(), Map.of(), Map.of(), List.of());
        Map<String, Object> payload = AiPromptProjection.project(adapter, o);
        var history = RuleSupport.maps(payload.get("events"));
        assertEquals(73, history.size());
        assertEquals(events.stream().map(e -> e.get("eventId")).filter(id -> !Set.of("assigned", "start", "cast", "private-vote", "vote-timeout").contains(id)).toList(),
                history.stream().map(e -> e.get("eventId")).toList());
        assertEquals("原始描述0", history.getFirst().get("content")); assertFalse(history.getFirst().containsKey("message"));
        assertEquals("RUNOFF", history.stream().filter(e -> "runoff".equals(e.get("eventId"))).findFirst().orElseThrow().get("phase"));
        assertEquals(Map.of("p0", "p1", "p1", "abstain"), history.get(65).get("votes"));
        assertFalse(history.toString().contains("tally")); assertFalse(history.toString().contains("REQUEST_FAILED"));
        assertEquals(1, json.valueToTree(payload).findValues("word").size(), "the assigned word appears in exactly one field");
    }

    @Test void undercoverPublishesOnlyTheEffectiveInitialSpyCountAndPhaseInstruction() {
        var adapter = new UndercoverRuleSet(words);
        for (String phase : List.of("DESCRIPTION", "TIE_DEFENSE", "VOTING", "RUNOFF")) {
            var players = java.util.stream.IntStream.range(0, 10).mapToObj(i -> Map.<String, Object>of("playerId", "p" + i, "alive", i < 4)).toList();
            for (String mode : List.of("auto", "manual")) {
                var o = new VisibleObservation("undercover", "i", phase, 4, "p0", "unused", Map.of(), players, Map.of("spyMode", mode, "spyCount", 3),
                        List.of(), Map.of(), List.of(), Map.of(), Map.of(), List.of());
                var payload = AiPromptProjection.project(adapter, o);
                assertEquals("auto".equals(mode) ? 2 : 3, RuleSupport.map(payload.get("rules")).get("spyCount"));
                assertEquals(adapter.instruction(o), payload.get("instruction"));
                assertTrue(adapter.knowledge(o).isEmpty());
            }
        }
    }

    @Test void directedReplyExamplePassesTheActualParserAndRequiredCitationCheck() throws Exception {
        int checked = 0;
        for (var scenario : new AiConversationScenarios().load()) {
            var o = scenario.observation(); var adapter = scenario.session().rule;
            if (adapter.conversation(o).replyTo().isEmpty()) continue;
            assertEquals("werewolf", o.gameId());
            var examples = com.aisocialgame.engine.v2.RuleSupport.maps(AiPromptProjection.project(adapter,o).get("outputExamples"));
            var example = examples.stream().filter(e -> "ANSWER_PLAYER".equals(com.aisocialgame.engine.v2.RuleSupport.map(e.get("action")).get("type"))).findFirst().orElseThrow();
            assertEquals(List.of(scenario.requiredEventId()), example.get("evidenceEventIds"));
            assertFalse(com.aisocialgame.engine.v2.RuleSupport.map(example.get("action")).containsKey("evidenceEventIds"));
            assertTrue(examples.stream().filter(e -> "SKIP".equals(com.aisocialgame.engine.v2.RuleSupport.map(e.get("action")).get("type"))).noneMatch(e -> e.containsKey("evidenceEventIds")));
            var client = org.mockito.Mockito.mock(com.aisocialgame.integration.grpc.client.AiGrpcClient.class);
            var safety = org.mockito.Mockito.mock(com.aisocialgame.service.safety.AiSafetyService.class);
            org.mockito.Mockito.when(safety.review(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(com.aisocialgame.service.safety.AiSafetyContext.class)))
                    .thenAnswer(c -> new com.aisocialgame.service.safety.AiSafetyResult("ALLOW","LOW","NONE","",c.getArgument(0),null));
            org.mockito.Mockito.when(client.chatCompletions(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(com.aisocialgame.service.ai.config.AiModelSelection.class),org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyInt()))
                    .thenReturn(new com.aisocialgame.integration.grpc.dto.AiChatResult(json.writeValueAsString(example),"mock-only",1,1));
            var properties = new com.aisocialgame.config.AppProperties(); properties.setProjectKey("offline"); properties.getAi().setDefaultModel("mock-only"); properties.getAi().setSystemUserId(1);
            var decision = new AiTurnGenerator(client,properties,scenario.session().memory,safety).generate(adapter,o,"example-only-"+checked++);
            assertFalse(decision.fallback(),scenario.id()+":"+decision.diagnostics());
            assertEquals(1,decision.diagnostics().get("calls"));
        }
        assertEquals(8,checked);
    }

    private JsonNode resolve(JsonNode node, JsonNode root, int depth) {
        assertTrue(depth < 25, "reference cycle");
        if (node.isObject() && node.size() == 1 && node.has("$ref")) {
            JsonNode target = root.at(node.path("$ref").asText()); assertFalse(target.isMissingNode(), node.toString());
            return resolve(target, root, depth + 1);
        }
        if (node.isObject()) {
            var result = json.createObjectNode(); node.fields().forEachRemaining(e -> result.set(e.getKey(), resolve(e.getValue(), root, depth + 1))); return result;
        }
        if (node.isArray()) { var result = json.createArrayNode(); node.forEach(n -> result.add(resolve(n, root, depth + 1))); return result; }
        return node;
    }
}
