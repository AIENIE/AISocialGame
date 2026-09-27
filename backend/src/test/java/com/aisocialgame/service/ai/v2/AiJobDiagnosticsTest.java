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
            assertFalse(AiJobDiagnostics.overdue(j, now.plusSeconds(60), ZoneId.of(zone)));
            assertTrue(AiJobDiagnostics.overdue(j, now.plusSeconds(61), ZoneId.of(zone)));
            j.setStatus("SUCCEEDED"); AiJobDiagnostics.completed(j, "MODEL_ACTION_APPLIED", now.plusSeconds(1));
            assertEquals(6000L, j.getDiagnostics().get("elapsedBeforeCommitMs"));
        }
    }
    @Test void clockRollbackIsMarkedAndLegacyRecoveryDoesNotInventEpochs() {
        Instant now = Instant.parse("2026-09-19T02:00:00Z"); AiTurnJob j = new AiTurnJob();
        j.setStartedAt(LocalDateTime.ofInstant(now.minusSeconds(61), ZoneId.of("Asia/Shanghai")));
        assertTrue(AiJobDiagnostics.overdue(j, now, ZoneId.of("Asia/Shanghai"))); assertTrue(j.getDiagnostics().isEmpty());
        j.getDiagnostics().put("queuedEpochMs", now.plusSeconds(5).toEpochMilli()); AiJobDiagnostics.started(j, now);
        assertFalse(j.getDiagnostics().containsKey("queueMs")); assertEquals("CLOCK_MOVED_BACKWARD", j.getDiagnostics().get("clockAnomaly"));
    }
    @Test void measurementsExcludeRawModelAndUntrustedExceptionFields() {
        var safe = AiJobDiagnostics.generation(Map.of("rawOutput", Map.of("secret", "hidden"), "exception", "credential",
                "calls", 1, "attempts", List.of(Map.of("rpcMs", 15L, "message", "secret"))));
        assertEquals(Map.of("calls", 1, "attempts", List.of(Map.of("rpcMs", 15L))), safe);
        assertFalse(safe.containsKey("promptTokens"), "missing usage is not zero");
    }

    @Test void additiveMigrationPreservesOldRowsAndIsGuardedForRepeatExecution() throws Exception {
        String sql = java.nio.file.Files.readString(java.nio.file.Path.of("sql/20260919_ai_turn_diagnostics.sql"));
        assertTrue(sql.contains("information_schema.columns")); assertTrue(sql.contains("column_name = 'diagnostics'"));
        assertTrue(sql.contains("'SELECT 1'")); assertFalse(sql.toUpperCase(Locale.ROOT).contains("UPDATE "));
        var matcher = java.util.regex.Pattern.compile("'((?:ALTER TABLE)[^']+)'").matcher(sql); assertTrue(matcher.find());
        String ddl = matcher.group(1);
        // Exercise the actual additive DDL in embedded MySQL mode; shared MySQL is never contacted.
        try (var db = java.sql.DriverManager.getConnection("jdbc:h2:mem:diagnostic_migration;MODE=MySQL;DATABASE_TO_LOWER=TRUE")) {
            try (var statement = db.createStatement()) {
                statement.execute("CREATE TABLE ai_turn_jobs (id VARCHAR(64) PRIMARY KEY, observation LONGTEXT)");
                statement.execute("INSERT INTO ai_turn_jobs VALUES ('legacy', '{\"events\":[]}')");
                for (int attempt = 0; attempt < 2; attempt++) {
                    boolean present;
                    try (var columns = db.getMetaData().getColumns(null, null, "ai_turn_jobs", "diagnostics")) { present = columns.next(); }
                    if (!present) statement.execute(ddl);
                }
                try (var row = statement.executeQuery("SELECT observation, diagnostics FROM ai_turn_jobs WHERE id='legacy'")) {
                    assertTrue(row.next()); assertEquals("{\"events\":[]}", row.getString(1)); assertNull(row.getString(2));
                }
            }
        }
        for (String file : List.of("sql/schema.sql", "sql/ai_turn_jobs.sql"))
            assertTrue(java.nio.file.Files.readString(java.nio.file.Path.of(file)).contains("`diagnostics` LONGTEXT NULL"));
    }
}
