package com.aisocialgame.service.ai.v2;

import com.aisocialgame.config.AppProperties;
import com.aisocialgame.integration.grpc.client.AiGrpcClient;
import com.aisocialgame.integration.grpc.dto.AiChatResult;
import com.aisocialgame.service.ai.config.AiModelSelection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class UndercoverSpeechComparisonTest {
    @TempDir Path directory;

    @Test void eightPairsUseTheSameObservationAndChangeOnlyThePhaseInstruction() throws Exception {
        var trials = UndercoverSpeechComparison.trials();
        assertEquals(UndercoverSpeechComparison.inputSha256(trials),
                UndercoverSpeechComparison.inputSha256(UndercoverSpeechComparison.trials()));
        var pairs = trials.stream()
                .collect(Collectors.groupingBy(UndercoverSpeechComparison.Trial::sampleId));
        assertEquals(8, pairs.size());
        assertEquals(Set.of("undercover:blank_first", "undercover:blank_last",
                "undercover:next_description", "undercover:runoff_description"), pairs.values().stream()
                .map(pair -> pair.getFirst().caseId()).collect(Collectors.toSet()));
        for (var pair : pairs.values()) {
            assertEquals(2, pair.size());
            var baseline = pair.getFirst();
            var candidate = pair.getLast();
            assertEquals(UndercoverSpeechComparison.BASELINE, baseline.promptVersion());
            assertEquals("undercover-v1.3", candidate.promptVersion());
            assertEquals(baseline.observation(), candidate.observation());
            var original = new LinkedHashMap<>(AiPromptProjection.project(baseline.adapter(), baseline.observation()));
            var changed = new LinkedHashMap<>(AiPromptProjection.project(candidate.adapter(), candidate.observation()));
            assertNotEquals(original.remove("instruction"), changed.remove("instruction"));
            assertEquals(original, changed);
        }
    }

    @Test void failedFirstAnswersConsumeOneSlotAndTheFullJournalStopsBeforeTheTransport() throws Exception {
        var client = mock(AiGrpcClient.class);
        var generator = new AiTurnGenerator(client, new AppProperties(), null, null);
        var active = new AtomicReference<UndercoverSpeechComparison.Attempt>();
        var sends = new AtomicInteger();
        var evidence = directory.resolve("comparison.json");
        var journal = directory.resolve("requests.jsonl");
        var selection = AiModelSelection.defaults("mock-only");
        var trials = UndercoverSpeechComparison.trials();
        UndercoverSpeechComparison.intercept(client, active::get, invocation -> {
            sends.incrementAndGet();
            return new AiChatResult("malformed reply", "mock-only", 10, 1);
        });
        try (var ledger = new AiRealismComparisonLedger(journal, UndercoverSpeechComparison.CALL_LIMIT)) {
            for (var trial : trials) {
                var attempt = new UndercoverSpeechComparison.Attempt(trial, ledger, evidence);
                active.set(attempt);
                var result = attempt.generate(generator, selection);
                assertEquals("REQUEST_FAILED", result.get("failure"));
                assertEquals("malformed reply", result.get("rawResponse"));
                assertEquals(attempt.jobId + ":0", result.get("requestId"));
                assertEquals(sends.get(), ledger.consumed());
            }
        }
        assertEquals(16, sends.get());
        try (var ledger = new AiRealismComparisonLedger(journal, UndercoverSpeechComparison.CALL_LIMIT)) {
            var extra = new UndercoverSpeechComparison.Attempt(trials.getFirst(), ledger, evidence);
            active.set(extra);
            assertThrows(AssertionError.class, () -> extra.generate(generator, selection));
            assertNull(extra.requestId);
            assertEquals(16, sends.get());
        }
    }
}
