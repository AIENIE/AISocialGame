package com.aisocialgame.service.ai.v2;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AiMemoryEntriesTest {
    private AiMemoryEntries.Parsed parse(Object value) {
        return AiMemoryEntries.proposals(value, true, Set.of("e1", "e2"), 3);
    }

    @Test void aliasesAndStringsHaveOneCanonicalShapeAndServerProvenance() {
        var parsed = parse(List.of("旧客户端的新猜想", Map.of("hypothesis", "猜想", "content", "猜想", "eventId", "e1", "confidence", .4)));
        assertTrue(parsed.errors().isEmpty());
        assertEquals(Map.of("text", "猜想", "evidenceEventIds", List.of("e1"), "confidence", .4, "round", 3, "source", "MODEL_PROPOSAL"), parsed.entries().getLast());
        assertEquals(List.of(), parsed.entries().getFirst().get("evidenceEventIds"));
        assertFalse(parsed.entries().getFirst().containsKey("confidence"));
    }

    @Test void conflictingAliasesAndMalformedShapesCannotBeStringifiedIntoMemory() {
        for (Object malformed : List.of(17, Map.of("text", Map.of("secret", "value")), Map.of("text", ""),
                Map.of("text", "a", "content", "b"), Map.of("text", "a", "eventId", "e1", "evidenceEventIds", List.of("e2")),
                Map.of("text", "a", "evidenceEventIds", "e1"), Map.of("text", "a", "evidenceEventIds", List.of(2)),
                Map.of("text", "a", "source", "FACT"), Map.of("text", "a", "round", 0), Map.of("confidence", .5))) {
            assertFalse(parse(List.of(malformed)).errors().isEmpty(), malformed.toString());
        }
        assertFalse(parse(Map.of("text", "not an array")).errors().isEmpty());
        assertTrue(parse(null).entries().isEmpty());
    }

    @Test void allEvidenceIsCheckedBeforeCappingAndEvenBeyondEightEntries() {
        List<String> refs = new ArrayList<>(List.of("e1", "e2", "e3", "e4", "e5", "e6", "secret"));
        assertEquals(List.of("INVISIBLE_MEMORY_EVIDENCE"), AiMemoryEntries.proposals(List.of(Map.of("text", "a", "evidenceEventIds", refs)),
                true, Set.of("e1", "e2", "e3", "e4", "e5", "e6"), 1).errors());
        List<Object> entries = new ArrayList<>(Collections.nCopies(8, "合法"));
        entries.add(Map.of("text", "第九条", "eventId", "secret"));
        assertEquals(List.of("INVISIBLE_MEMORY_EVIDENCE"), parse(entries).errors());
    }

    @Test void confidenceMustBeFiniteNumericAndOnlyAppliesToHypotheses() {
        for (Object value : List.of(-.1, 1.1, Double.NaN, Double.POSITIVE_INFINITY, "0.5"))
            assertEquals(List.of("INVALID_MEMORY_CONFIDENCE"), parse(List.of(Map.of("text", "a", "confidence", value))).errors());
        assertEquals(List.of("INVALID_MEMORY_CONFIDENCE"), AiMemoryEntries.proposals(List.of(Map.of("text", "a", "confidence", .5)), false, Set.of(), 1).errors());
        assertTrue(parse(List.of(Map.of("text", "a", "confidence", 0), Map.of("text", "b", "confidence", 1))).errors().isEmpty());
    }

    @Test void oldStringsRemainUnverifiedWithoutInventedEvidenceOrMetadata() {
        String opaque = "{hypothesis=以前被截断的对象, evidenceEventIds=[";
        var old = AiMemoryEntries.stored(List.of(opaque), true, false).getFirst();
        assertEquals(opaque, old.get("text"));
        assertEquals("LEGACY_UNVERIFIED", old.get("source"));
        assertNull(old.get("round"));
        assertEquals(List.of(), old.get("evidenceEventIds"));
        assertFalse(old.containsKey("confidence"));
        assertEquals(List.of(old), AiMemoryEntries.stored(List.of(old), true, true));
    }

    @Test void snapshotDoesNotTrustUnversionedProvenanceAndDoesNotMutateObjects() {
        Map<String, Object> old = new LinkedHashMap<>(Map.of("text", "猜测", "eventId", "e1", "round", 77, "source", "MODEL_PROPOSAL"));
        Map<String, Object> before = new LinkedHashMap<>(old);
        var read = AiMemoryEntries.stored(List.of(old), true, false).getFirst();
        assertEquals(before, old);
        assertEquals("LEGACY_UNVERIFIED", read.get("source"));
        assertNull(read.get("round"));
        assertEquals(List.of("e1"), read.get("evidenceEventIds"));
    }

    @Test void repeatedTextMergesEvidenceAndLatestValidValuesWithoutMutatingInputs() {
        var first = parse(List.of(Map.of("text", " 猜测 ", "confidence", .8, "eventId", "e1"))).entries();
        var second = AiMemoryEntries.proposals(List.of(Map.of("content", "猜测", "confidence", .2, "eventId", "e2")), true, Set.of("e2"), 4).entries();
        var merged = AiMemoryEntries.merge(first, second);
        assertEquals(1, merged.size());
        assertEquals(List.of("e1", "e2"), merged.getFirst().get("evidenceEventIds"));
        assertEquals(.2, merged.getFirst().get("confidence"));
        assertEquals(4, merged.getFirst().get("round"));
        assertEquals(.8, first.getFirst().get("confidence"));
        assertEquals(merged, AiMemoryEntries.merge(merged, second));
        var withoutConfidence = AiMemoryEntries.merge(merged, parse(List.of("猜测")).entries());
        assertEquals(.2, withoutConfidence.getFirst().get("confidence"));
    }

    @Test void entriesEvidenceAndUnicodeTextStayBounded() {
        String text = "😀".repeat(161);
        var truncated = parse(List.of(text)).entries().getFirst().get("text").toString();
        assertEquals(160, truncated.codePointCount(0, truncated.length()));
        assertEquals("😀".repeat(160), truncated);
        List<Map<String, Object>> accumulated = List.of();
        Set<String> refs = new LinkedHashSet<>();
        for (int i = 0; i < 10; i++) refs.add("e" + i);
        for (int round = 0; round < 3; round++) {
            List<Object> entries = new ArrayList<>();
            for (int i = 0; i < 10; i++) entries.add(Map.of("text", round + ":" + i, "evidenceEventIds", new ArrayList<>(refs)));
            var parsed = AiMemoryEntries.proposals(entries, false, refs, round);
            assertEquals(8, parsed.entries().size());
            assertEquals(6, ((List<?>) parsed.entries().getFirst().get("evidenceEventIds")).size());
            accumulated = AiMemoryEntries.merge(accumulated, parsed.entries());
        }
        assertEquals(16, accumulated.size());
        assertEquals("1:0", accumulated.getFirst().get("text"));
        assertEquals("2:7", accumulated.getLast().get("text"));
    }
}
