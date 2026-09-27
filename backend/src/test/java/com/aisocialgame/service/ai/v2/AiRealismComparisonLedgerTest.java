package com.aisocialgame.service.ai.v2;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AiRealismComparisonLedgerTest {
    @Test void interruptedReservationsAndRerunsShareOneNinetyCallCeiling(@TempDir Path folder) throws Exception {
        Path journal = folder.resolve("comparison-budget.jsonl");
        try (var first = new AiRealismComparisonLedger(journal, 90)) {
            for (int i = 0; i < 50; i++) assertEquals(i + 1, first.reserve("first", "scenario", "v2", "request-" + i));
            // No response records are needed: every reservation may already have reached the gateway.
        }
        try (var rerun = new AiRealismComparisonLedger(journal, 90)) {
            assertEquals(50, rerun.consumed());
            for (int i = 50; i < 90; i++) assertEquals(i + 1, rerun.reserve("rerun", "scenario", "v2", "request-" + i));
            assertThrows(IOException.class, () -> rerun.reserve("rerun", "scenario", "v2", "over-budget"));
        }
        try (var third = new AiRealismComparisonLedger(journal, 90)) {
            assertEquals(90, third.consumed());
            assertThrows(IOException.class, () -> third.reserve("third", "scenario", "legacy", "over-budget-again"));
        }
        assertEquals(90, Files.readAllLines(journal).size());
    }

    @Test void partialOrCorruptJournalsCannotSilentlyResetSpentBudget(@TempDir Path folder) throws Exception {
        Path partial = folder.resolve("partial.jsonl");
        Files.writeString(partial, "{\"event\":\"ATTEMPT_RESERVED\",\"comparisonAttempt\":1}");
        assertThrows(IOException.class, () -> new AiRealismComparisonLedger(partial, 90));
        Path gap = folder.resolve("gap.jsonl");
        Files.writeString(gap, "{\"event\":\"ATTEMPT_RESERVED\",\"comparisonAttempt\":2}\n");
        assertThrows(IOException.class, () -> new AiRealismComparisonLedger(gap, 90));
    }

    @Test void concurrentComparisonCannotAcquireAnAlreadyOwnedJournal(@TempDir Path folder) throws Exception {
        Path journal = folder.resolve("comparison-budget.jsonl");
        try (var owner = new AiRealismComparisonLedger(journal, 90)) {
            assertThrows(Exception.class, () -> new AiRealismComparisonLedger(journal, 90));
            assertEquals(1, owner.reserve("owner", "scenario", "legacy", "one"));
        }
        try (var next = new AiRealismComparisonLedger(journal, 90)) { assertEquals(1, next.consumed()); }
    }
}
