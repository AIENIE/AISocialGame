package com.aisocialgame.service.ai.v2;

import com.aisocialgame.engine.v2.turtlesoup.*;
import com.aisocialgame.engine.v2.undercover.*;
import com.aisocialgame.engine.v2.werewolf.*;
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

    @Test void allGamesAndPresetsRetainExactDataWhileLongHistoryShrinks() throws Exception {
        List<Map<String, Object>> samples = new ArrayList<>();
        for (var scenario : fixtures.load()) for (var persona : PersonaPresets.all()) for (boolean longHistory : List.of(false, true)) {
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
        var adapter = new UndercoverRuleSet(words);
        var o = new VisibleObservation("undercover", "i", "DESCRIPTION", 1, "p", "SPEAK", Map.of(), List.of(), Map.of(),
                List.of(Map.of("eventId", "e", "actorId", "p", "type", "SPEAK", "message", "逐字保留的引用", "data", Map.of("content", "逐字保留的引用"))),
                Map.of(), List.of(), Map.of(), Map.of(), List.of());
        JsonNode projected = json.valueToTree(AiPromptProjection.project(adapter, o));
        assertTrue(projected.at("/observation/events/0/data/content").has("$ref"));
        assertEquals(json.valueToTree(o.events()), resolve(projected.at("/observation/events"), projected, 0));
        assertEquals("逐字保留的引用", resolve(projected.at("/informationSources/events/0/value/statement"), projected, 0).asText());
    }

    @Test void directedReplyExamplePassesTheActualParserAndRequiredCitationCheck() throws Exception {
        int checked = 0;
        for (var scenario : new AiConversationScenarios().load()) {
            var o = scenario.observation(); var adapter = scenario.session().rule;
            if (adapter.conversation(o).replyTo().isEmpty()) continue;
            var examples = com.aisocialgame.engine.v2.RuleSupport.maps(AiPromptProjection.project(adapter,o).get("outputExamples"));
            var example = examples.stream().filter(e -> "ANSWER_PLAYER".equals(com.aisocialgame.engine.v2.RuleSupport.map(e.get("action")).get("type"))).findFirst().orElseThrow();
            assertEquals(List.of(scenario.requiredEventId()), example.get("evidenceEventIds"));
            assertFalse(com.aisocialgame.engine.v2.RuleSupport.map(example.get("action")).containsKey("evidenceEventIds"));
            assertTrue(examples.stream().filter(e -> "SKIP".equals(com.aisocialgame.engine.v2.RuleSupport.map(e.get("action")).get("type"))).noneMatch(e -> e.containsKey("evidenceEventIds")));
            var client = org.mockito.Mockito.mock(com.aisocialgame.integration.grpc.client.AiGrpcClient.class);
            var safety = org.mockito.Mockito.mock(com.aisocialgame.service.safety.AiSafetyService.class);
            org.mockito.Mockito.when(safety.review(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(com.aisocialgame.service.safety.AiSafetyContext.class)))
                    .thenAnswer(c -> new com.aisocialgame.service.safety.AiSafetyResult("ALLOW","LOW","NONE","",c.getArgument(0),null));
            org.mockito.Mockito.when(client.chatCompletions(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyInt()))
                    .thenReturn(new com.aisocialgame.integration.grpc.dto.AiChatResult(json.writeValueAsString(example),"mock-only",1,1));
            var properties = new com.aisocialgame.config.AppProperties(); properties.setProjectKey("offline"); properties.getAi().setDefaultModel("mock-only"); properties.getAi().setSystemUserId(1);
            var decision = new AiTurnGenerator(client,properties,scenario.session().memory,safety).generate(adapter,o,"example-only-"+checked++);
            assertFalse(decision.fallback(),scenario.id()+":"+decision.diagnostics());
            assertEquals(1,decision.diagnostics().get("calls"));
        }
        assertEquals(16,checked);
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
