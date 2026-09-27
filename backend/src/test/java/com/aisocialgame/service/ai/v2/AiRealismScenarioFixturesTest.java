package com.aisocialgame.service.ai.v2;

import com.aisocialgame.engine.v2.turtlesoup.TurtleSoupCaseCatalog;
import com.aisocialgame.engine.v2.turtlesoup.TurtleSoupRuleSet;
import com.aisocialgame.engine.v2.undercover.UndercoverRuleSet;
import com.aisocialgame.engine.v2.undercover.UndercoverWordCatalog;
import com.aisocialgame.engine.v2.werewolf.WerewolfRuleSet;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AiRealismScenarioFixturesTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final UndercoverWordCatalog words = new UndercoverWordCatalog();
    private final TurtleSoupCaseCatalog soups = new TurtleSoupCaseCatalog(json);
    private final AiRealismScenarioFixtures fixtures = new AiRealismScenarioFixtures(
            List.of(new UndercoverRuleSet(words), new WerewolfRuleSet(), new TurtleSoupRuleSet(soups)), words, soups);

    @Test void thirtyUniqueSocialFixturesExcludeGoldExpectationsFromModelInput() throws Exception {
        var scenarios = fixtures.load();
        assertEquals(30, scenarios.size());
        for (String game : List.of("undercover", "werewolf", "turtle_soup")) assertEquals(10, scenarios.stream().filter(s -> game.equals(s.gameId())).count());
        for (var scenario : scenarios) {
            var observation = scenario.observation();
            assertFalse(observation.legalActions().isEmpty());
            assertEquals(observation.events().size(), observation.events().stream().map(e -> e.get("eventId")).distinct().count());
            String input = json.writeValueAsString(observation);
            assertFalse(input.contains("goldExpectations")); assertFalse(input.contains("prohibited"));
            assertTrue(scenario.expectations().containsKey("behavior"));
        }
    }

    @Test void subsetSelectionIsExactAndCannotSilentlyRunAllOnInvalidIds() throws Exception {
        var all = fixtures.load();
        assertEquals(all, AiRealismScenarioFixtures.select(all, ""));
        assertEquals(List.of(all.get(8), all.get(9)), AiRealismScenarioFixtures.select(all, all.get(8).id() + "," + all.get(9).id()));
        assertThrows(IllegalArgumentException.class, () -> AiRealismScenarioFixtures.select(all, "unknown"));
        assertThrows(IllegalArgumentException.class, () -> AiRealismScenarioFixtures.select(all, all.getFirst().id() + ","));
        assertThrows(IllegalArgumentException.class, () -> AiRealismScenarioFixtures.select(all, all.getFirst().id() + "," + all.getFirst().id()));
    }

    @Test void offlineFallbackSamplesRemainLegalAndContainOnlyFixtureEvidence() throws Exception {
        var samples = new java.util.ArrayList<java.util.Map<String, Object>>();
        for (var scenario : fixtures.load()) {
            var o = scenario.observation();
            var decision = AiTurnDecision.fallback(scenario.adapter().fallback(o));
            assertTrue(o.legalActions().stream().anyMatch(a -> a.type().equals(decision.action().getType())), scenario.id());
            assertTrue(scenario.adapter().validateDecision(o, decision).isEmpty(), scenario.id());
            samples.add(java.util.Map.of("scenarioId", scenario.id(), "decision", decision, "continuity", AiGrounding.context(o)));
        }
        var target = Path.of("target", "realism-v2-offline-samples.json");
        java.nio.file.Files.createDirectories(target.getParent());
        json.writerWithDefaultPrettyPrinter().writeValue(target.toFile(), java.util.Map.of("source", "authored fixtures; no model calls",
                "promptVersion", AiTurnGenerator.PROMPT_VERSION, "fallbackRate", 1.0, "samples", samples));
    }

    @Test void blankAndCooperativePlayersReceiveNoHiddenCatalogTruth() throws Exception {
        for (var scenario : fixtures.load()) {
            var observation = scenario.observation();
            if (Boolean.TRUE.equals(observation.privateFacts().get("blank"))) {
                assertEquals("", observation.privateFacts().get("word"));
                assertFalse(observation.privateFacts().containsKey("wordKnowledge"));
                assertEquals(1, observation.knowledge().size());
            }
            if ("turtle_soup".equals(scenario.gameId())) {
                assertTrue(observation.privateFacts().isEmpty());
                assertFalse(json.writeValueAsString(observation).contains(soups.require(scenario.caseId()).solution()));
                assertEquals("ACTUAL_LEGACY_SCRIPT_NO_MODEL", scenario.baselineMatch());
                assertTrue(observation.legalActions().stream().noneMatch(a -> Set.of("HOST_VERDICT", "SUBMIT_SOLUTION", "REQUEST_HINT").contains(a.type())));
            }
            if (scenario.id().endsWith("reserve_human_budget")) assertTrue(observation.legalActions().stream().noneMatch(a -> "ASK_QUESTION".equals(a.type())));
        }
    }

    @Test void privateKnowledgeStaysPrivateAndUnsupportedBaselinesAreLabeled() throws Exception {
        for (var scenario : fixtures.load()) {
            var observation = scenario.observation();
            var legacy = fixtures.legacyState(scenario);
            if (scenario.id().endsWith("werewolf_wolf_disagreement")) {
                String publicLog = json.writeValueAsString(legacy.getLogs());
                assertFalse(publicLog.contains("四号可能会被守"));
                assertTrue(json.writeValueAsString(observation.privateFacts()).contains("四号可能会被守"));
                assertTrue(observation.events().stream().anyMatch(e -> "w1".equals(e.get("eventId"))));
            }
            if ("werewolf".equals(scenario.gameId()) && "VILLAGER".equals(observation.self().get("role"))) {
                for (String secret : List.of("seerChecks", "wolfTeam", "wolfCouncil", "guardHistory", "wolfTarget", "truth")) assertFalse(observation.privateFacts().containsKey(secret));
                assertTrue(observation.players().stream().filter(p -> !observation.actorId().equals(p.get("playerId"))).noneMatch(p -> p.containsKey("role")));
            }
            if ("SEER".equals(observation.self().get("role"))) {
                assertTrue(observation.events().stream().anyMatch(e -> "s1".equals(e.get("eventId"))));
                assertTrue(observation.events().stream().anyMatch(e -> "s2".equals(e.get("eventId"))));
                assertFalse(json.writeValueAsString(legacy.getLogs()).contains("本人的查验"));
            }
            if (Set.of("GUARD", "HUNTER").contains(String.valueOf(observation.self().get("role")))) assertTrue(scenario.baselineMatch().startsWith("APPROXIMATE"));
            if (scenario.id().endsWith("werewolf_guard_repeat_blocked")) {
                var protect = observation.legalActions().stream().filter(a -> "GUARD_PROTECT".equals(a.nightAction())).findFirst().orElseThrow();
                assertEquals("NIGHT_ACTION", protect.type()); assertFalse(protect.targets().contains("p2"));
            }
        }
    }

    @Test void reusableHostFixturesIncludeAllOneHundredFiftyAuthoredCases() {
        var semantic = fixtures.turtleHostFixtures();
        assertEquals(150, semantic.size());
        assertEquals(120, semantic.stream().filter(f -> "ASK_QUESTION".equals(f.get("type"))).count());
        assertEquals(30, semantic.stream().filter(f -> "SUBMIT_SOLUTION".equals(f.get("type"))).count());
    }

    @Test void specializedRolesAndBlankEndgameUseSupportedInitialBoards() throws Exception {
        for (var scenario : fixtures.load()) {
            var observation = scenario.observation();
            if ("HUNTER".equals(observation.self().get("role"))) assertEquals(9, observation.rules().get("playerCount"));
            if ("IDIOT".equals(observation.self().get("role"))) assertEquals(12, observation.rules().get("playerCount"));
            if ("GUARD".equals(observation.self().get("role"))) assertEquals("guard", observation.rules().get("template"));
            if (scenario.id().endsWith("pressure_blank_final")) {
                assertEquals(6, observation.rules().get("playerCount")); assertEquals(3, observation.players().size());
            }
            for (var capability : observation.legalActions()) for (String target : capability.targets()) {
                assertTrue(observation.players().stream().anyMatch(p -> target.equals(p.get("playerId")) && Boolean.TRUE.equals(p.get("alive"))));
            }
        }
    }

    @Test void onlyHostSemanticInputsReceiveAuthoredTruthAndNeverGoldVerdicts() throws Exception {
        for (var fixture : fixtures.turtleHostFixtures()) {
            var observation = fixtures.turtleHostObservation(fixture);
            assertEquals("$host", observation.actorId());
            assertTrue(json.writeValueAsString(observation.privateFacts()).contains(soups.require(fixture.get("caseId").toString()).solution()));
            String input = json.writeValueAsString(observation);
            for (String gold : List.of("expectedVerdict", "expectedFactIds", "expectedSolved", "questionFixtures", "solutionFixtures")) assertFalse(input.contains(gold));
            assertEquals("HOST_VERDICT", observation.legalActions().getFirst().type());
            assertTrue(observation.memory().isEmpty());
        }
    }

    @Test void remoteHarnessIsExplicitlyOptInAndNeverOverwritesEvidence(@TempDir Path external) throws Exception {
        var condition = AiRealismComparisonIntegrationTest.class.getAnnotation(EnabledIfEnvironmentVariable.class);
        assertEquals("AI_REALISM_COMPARISON", condition.named()); assertEquals("1", condition.matches());
        Path requested = external.resolve("new-run");
        Path created = AiRealismComparisonIntegrationTest.createEvidenceDirectory(requested.toString());
        assertEquals(external.toRealPath(), created.toRealPath().getParent());
        assertThrows(FileAlreadyExistsException.class, () -> AiRealismComparisonIntegrationTest.createEvidenceDirectory(requested.toString()));
        assertThrows(IllegalArgumentException.class, () -> AiRealismComparisonIntegrationTest.createEvidenceDirectory("relative-path"));
        assertThrows(IllegalArgumentException.class, () -> AiRealismComparisonIntegrationTest.createEvidenceDirectory(Path.of("").toAbsolutePath().resolve("evidence-inside-checkout").toString()));
    }
}
