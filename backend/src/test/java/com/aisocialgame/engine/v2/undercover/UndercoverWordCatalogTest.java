package com.aisocialgame.engine.v2.undercover;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class UndercoverWordCatalogTest {
    private final UndercoverWordCatalog catalog = new UndercoverWordCatalog();

    @Test void has120ReviewedPairsWithIndependentKnowledgeAndFourRealPacks() throws Exception {
        assertEquals(120, catalog.all().size());
        Set<String> ids = new HashSet<>();
        ObjectMapper json = new ObjectMapper();
        for (String category : UndercoverWordCatalog.CATEGORIES) {
            var entries = catalog.category(category);
            assertEquals(30, entries.size());
            assertEquals(10, entries.stream().filter(p -> "EASY".equals(p.difficulty())).count());
            assertEquals(15, entries.stream().filter(p -> "MEDIUM".equals(p.difficulty())).count());
            assertEquals(5, entries.stream().filter(p -> "HARD".equals(p.difficulty())).count());
            for (var pair : entries) {
                assertTrue(ids.add(pair.id()));
                assertEquals("CURATED_REVIEWED", pair.reviewStatus());
                assertNotEquals(pair.sideA().word(), pair.sideB().word());
                for (var side : List.of(pair.sideA(), pair.sideB())) {
                    assertTrue(side.attributes().size() >= 3);
                    assertFalse(side.definition().isBlank());
                    assertFalse(side.scenes().isEmpty());
                    assertTrue(side.forbiddenTerms().contains(side.word()));
                    assertEquals(side, catalog.ownKnowledge(side.word()).orElseThrow());
                }
                assertFalse(json.writeValueAsString(pair.sideA()).contains(pair.sideB().word()), pair.id());
                assertFalse(json.writeValueAsString(pair.sideB()).contains(pair.sideA().word()), pair.id());
            }
        }
        assertTrue(catalog.ownKnowledge("").isEmpty());
        assertTrue(catalog.ownKnowledge(null).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> catalog.category("missing"));
    }

    @Test void drawUsesTheSelectedPackAndAvoidsKnownPairsUntilExhausted() {
        Set<String> used = new HashSet<>();
        Random random = new Random(8);
        for (int i = 0; i < 30; i++) {
            var pair = catalog.draw("tech", used, random);
            assertEquals("tech", pair.category());
            assertTrue(used.add(pair.id()));
        }
        assertTrue(used.contains(catalog.draw("tech", used, random).id()));
    }

    @Test void secretsCannotBeBypassedWithWhitespacePunctuationAliasesOrFullWidthLetters() {
        assertTrue(catalog.containsForbidden("我说的是冰 淇-淋", "冰淇淋"));
        assertTrue(catalog.containsForbidden("冰激凌", "冰淇淋"));
        assertTrue(catalog.containsForbidden("ＳＳＤ", "固态硬盘"));
        assertTrue(catalog.containsForbidden("魔\u200b法石", "魔法石"));
        assertFalse(catalog.containsForbidden("吃起来冰冰凉凉的", "冰淇淋"));
        assertFalse(catalog.containsForbidden("我还不确定", ""));
    }

    @Test void fixtureCoversTenDistinctSocialSituationsWithoutARequiredModelCall() throws Exception {
        try (var stream = getClass().getResourceAsStream("/ai-scenarios/undercover.json")) {
            assertNotNull(stream);
            var root = new ObjectMapper().readTree(stream);
            assertEquals(10, root.get("scenarios").size());
            Set<String> ids = new HashSet<>();
            root.get("scenarios").forEach(scenario -> {
                assertTrue(ids.add(scenario.get("id").asText()));
                assertTrue(scenario.get("expected").size() >= 3);
                assertTrue(scenario.get("prohibited").size() >= 2);
            });
            assertTrue(ids.containsAll(Set.of("blank_first", "blank_last", "wrongly_accused", "meaning_conflict", "copying",
                    "evidence_reversal", "wrong_vote_regret", "tied_defense", "pressure_blank_final", "secret_injection")));
        }
    }
}
