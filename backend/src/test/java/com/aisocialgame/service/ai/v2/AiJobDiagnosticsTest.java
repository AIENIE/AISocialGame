package com.aisocialgame.service.ai.v2;

import com.aisocialgame.model.AiTurnJob;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AiJobDiagnosticsTest {
    @Test void epochQueueAndRecoveryAreIndependentOfRuntimeZone() {
        Instant now = Instant.parse("2026-09-19T02:00:00Z");
        for (String zone : List.of("UTC", "Asia/Shanghai")) {
            AiTurnJob j = new AiTurnJob(); AiJobDiagnostics.initialize(j, now.minusSeconds(5), ZoneId.of(zone));
            j.getDiagnostics().put("queuedEpochMs", now.minusSeconds(4).toEpochMilli()); AiJobDiagnostics.started(j, now);
            assertEquals(4000L, j.getDiagnostics().get("queueMs"));
            assertFalse(AiJobDiagnostics.overdue(j, now.plusSeconds(60)));
            assertTrue(AiJobDiagnostics.overdue(j, now.plusSeconds(61)));
            j.setStatus("SUCCEEDED"); AiJobDiagnostics.completed(j, "MODEL_ACTION_APPLIED", now.plusSeconds(1));
            assertEquals(6000L, j.getDiagnostics().get("elapsedBeforeCommitMs"));
        }
    }
    @Test void clockRollbackIsMarkedAndMissingEpochCannotBeGuessedFromLocalTime() {
        Instant now = Instant.parse("2026-09-19T02:00:00Z"); AiTurnJob j = new AiTurnJob();
        j.setStartedAt(LocalDateTime.ofInstant(now.minusSeconds(61), ZoneId.of("Asia/Shanghai")));
        assertFalse(AiJobDiagnostics.overdue(j, now)); assertTrue(j.getDiagnostics().isEmpty());
        j.getDiagnostics().put("queuedEpochMs", now.plusSeconds(5).toEpochMilli()); AiJobDiagnostics.started(j, now);
        assertFalse(j.getDiagnostics().containsKey("queueMs")); assertEquals("CLOCK_MOVED_BACKWARD", j.getDiagnostics().get("clockAnomaly"));
    }
    @Test void measurementsExcludeRawModelAndUntrustedExceptionFields() {
        var safe = AiJobDiagnostics.generation(Map.of("rawOutput", Map.of("secret", "hidden"), "exception", "credential",
                "calls", 1, "attempts", List.of(Map.of("rpcMs", 15L, "message", "secret"))));
        assertEquals(Map.of("calls", 1, "attempts", List.of(Map.of("rpcMs", 15L))), safe);
        assertFalse(safe.containsKey("promptTokens"), "missing usage is not zero");
    }

}
