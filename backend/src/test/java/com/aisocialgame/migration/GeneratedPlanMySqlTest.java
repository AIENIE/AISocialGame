package com.aisocialgame.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import static com.aisocialgame.migration.ClosureMySqlSupport.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "AIENIE_CLOSURE_MYSQL", matches = "1")
class GeneratedPlanMySqlTest {
    @TempDir Path temporary;

    @Test
    void generatedReleasePlansPreserveHistoryAndResumePartialDdl() throws Exception {
        var generated = new GeneratedMigrationFixture(temporary);
        Path output = generated.output;
        var ledger = generated.ledger;
        var entries = new LinkedHashMap<Integer, ProductionSocialMigrationMain.MigrationEntry>();
        for (var value : ledger.path("entries")) {
            var entry = new ProductionSocialMigrationMain.MigrationEntry(value.path("ordinal").asInt(), output.resolve("sql").resolve(Path.of(value.path("path").asText()).getFileName()), value.path("sha256").asText());
            entries.put(entry.ordinal(), entry);
        }
        List<List<String>> freshColumns = null, freshIndexes = null;
        for (String suffix : List.of("plan_fresh", "plan_legacy", "plan_recorded")) {
            try (var server = connect(null); var statement = server.createStatement()) {
                statement.execute("CREATE DATABASE `" + database(suffix) + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
            }
            boolean fresh = suffix.equals("plan_fresh");
            var selected = ledger.path("execution_plans").get(fresh ? 1 : 0);
            var ordinals = new java.util.ArrayList<Integer>();
            selected.path("ordinals").forEach(value -> ordinals.add(value.asInt()));
            var plan = new ProductionSocialMigrationMain.MigrationPlan(selected.path("id").asText(), "sha256:" + "a".repeat(64), ordinals.stream().map(entries::get).toList(), entries);
            try (var connection = connect(suffix)) {
                if (!fresh) {
                    var baseline = new ProductionSocialMigrationMain.MigrationPlan("fresh-empty-schema", plan.checksum(), List.of(entries.get(1)), entries);
                    ProductionSocialMigrationMain.applyPending(connection, baseline, baseline.selectedEntries());
                    try (var statement = connection.createStatement()) {
                        statement.execute("INSERT INTO users(id,email,password,nickname,coins,level) VALUES('history','history@example.invalid','unused','history',19,3)");
                        if (suffix.equals("plan_legacy")) {
                            statement.execute("DROP TABLE aienie_sql_migration_ledger");
                            // Simulate a crash after the first ADD COLUMN in the old upgrade.
                            statement.execute("ALTER TABLE rooms DROP COLUMN version, DROP INDEX idx_rooms_game_status_created");
                        }
                    }
                }
                ProductionSocialMigrationMain.applyPending(connection, plan, ProductionSocialMigrationMain.pendingEntries(connection, plan));
                ProductionSocialMigrationMain.validateSchema(connection, plan);
                assertTrue(ProductionSocialMigrationMain.pendingEntries(connection, plan).isEmpty());
                ProductionSocialMigrationMain.applyPending(connection, plan, ProductionSocialMigrationMain.pendingEntries(connection, plan));
                var columns = ClosureMySqlMigrationTest.structure(connection);
                var indexes = ClosureMySqlMigrationTest.indexes(connection);
                if (fresh) { freshColumns = columns; freshIndexes = indexes; }
                else {
                    assertEquals(freshColumns, columns);
                    assertEquals(freshIndexes, indexes);
                    assertEquals(List.of(List.of("19", "3")), ClosureMySqlMigrationTest.rows(connection, "SELECT coins,level FROM users WHERE id='history'"));
                }
                verifyMissingColumnAndIndexAreRejected(connection, plan);
            }
        }
        evidence("generated-release-plans", java.util.Map.of("plans", 3, "entries", entries.size(), "repeatable", true, "historyPreserved", true, "schemaNegatives", true));
    }

    @Test
    void traceMigrationResumesAfterImplicitDdlAndBackfillsOnlyRecordedInstances() throws Exception {
        var generated = new GeneratedMigrationFixture(temporary);
        var ledger = generated.ledger;
        var entries = new LinkedHashMap<Integer, ProductionSocialMigrationMain.MigrationEntry>();
        for (var value : ledger.path("entries")) {
            var path = generated.output.resolve("sql").resolve(Path.of(value.path("path").asText()).getFileName());
            entries.put(value.path("ordinal").asInt(), new ProductionSocialMigrationMain.MigrationEntry(
                    value.path("ordinal").asInt(), path, value.path("sha256").asText()));
        }
        var selected = List.of(1, 4, 5, 6, 7, 8, 9, 10);
        var plan = new ProductionSocialMigrationMain.MigrationPlan("fresh-empty-schema", "sha256:" + "a".repeat(64),
                selected.stream().map(entries::get).toList(), entries);
        for (boolean indexAlreadyCreated : List.of(false, true)) {
            String suffix = indexAlreadyCreated ? "trace_index_partial" : "trace_column_partial";
            try (var server = connect(null); var statement = server.createStatement()) {
                statement.execute("CREATE DATABASE `" + database(suffix) + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
            }
            try (var connection = connect(suffix); var statement = connection.createStatement()) {
                ProductionSocialMigrationMain.applyPending(connection, plan, plan.selectedEntries().subList(0, 6));
                statement.execute("INSERT INTO ai_decision_traces(game_id,action,room_id,quality) VALUES"
                        + "('WEREWOLF','SPEAK','room-a','{\"instanceId\":\"archive-known\"}'),"
                        + "('WEREWOLF','SPEAK','room-a','{\"other\":1}'),"
                        + "('WEREWOLF','SPEAK','room-a','invalid-json')");
                statement.execute("ALTER TABLE ai_decision_traces ADD COLUMN instance_id VARCHAR(96) NULL AFTER room_id");
                if (indexAlreadyCreated) statement.execute("CREATE INDEX idx_ai_trace_instance ON ai_decision_traces(instance_id, fallback, id)");
                ProductionSocialMigrationMain.applyPending(connection, plan, ProductionSocialMigrationMain.pendingEntries(connection, plan));
                ProductionSocialMigrationMain.validateSchema(connection, plan);
                assertEquals(List.of(List.of("archive-known"), List.of("NULL"), List.of("NULL")),
                        ClosureMySqlMigrationTest.rows(connection,
                                "SELECT COALESCE(instance_id,'NULL') FROM ai_decision_traces ORDER BY id"));
                assertEquals(List.of(List.of("1")), ClosureMySqlMigrationTest.rows(connection,
                        "SELECT COUNT(*) FROM ai_decision_traces WHERE instance_id='archive-known'"));
                assertTrue(ProductionSocialMigrationMain.pendingEntries(connection, plan).isEmpty());
            }
        }
        evidence("trace-instance-migration", java.util.Map.of("partialDdlResume", true,
                "explicitInstanceBackfill", true, "unknownHistoryPreserved", true));
    }

    @Test
    void publicCursorMigrationResumesPartialDdlWithoutExposingPrivatePositions() throws Exception {
        var generated = new GeneratedMigrationFixture(temporary);
        var entries = new LinkedHashMap<Integer, ProductionSocialMigrationMain.MigrationEntry>();
        for (var value : generated.ledger.path("entries")) {
            var path = generated.output.resolve("sql").resolve(Path.of(value.path("path").asText()).getFileName());
            entries.put(value.path("ordinal").asInt(), new ProductionSocialMigrationMain.MigrationEntry(
                    value.path("ordinal").asInt(), path, value.path("sha256").asText()));
        }
        var selected = List.of(1, 4, 5, 6, 7, 8, 9, 10);
        var plan = new ProductionSocialMigrationMain.MigrationPlan("fresh-empty-schema", "sha256:" + "a".repeat(64),
                selected.stream().map(entries::get).toList(), entries);
        for (boolean indexAlreadyCreated : List.of(false, true)) {
            String suffix = indexAlreadyCreated ? "public_index_partial" : "public_column_partial";
            try (var server = connect(null); var statement = server.createStatement()) {
                statement.execute("CREATE DATABASE `" + database(suffix) + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
            }
            try (var connection = connect(suffix); var statement = connection.createStatement()) {
                ProductionSocialMigrationMain.applyPending(connection, plan, plan.selectedEntries().subList(0, 7));
                statement.execute("INSERT INTO game_events(archive_id,room_id,game_id,seq,event_type,visibility,round_number) VALUES "
                        + "('archive-a','room-a','undercover',1,'speech','PUBLIC',1),"
                        + "('archive-a','room-a','undercover',2,'secret','PRIVATE',1),"
                        + "('archive-a','room-a','undercover',3,'speech','PUBLIC',1)");
                statement.execute("ALTER TABLE game_events ADD COLUMN public_seq BIGINT NULL AFTER seq");
                if (indexAlreadyCreated) statement.execute("CREATE UNIQUE INDEX uk_game_events_archive_public_seq ON game_events(archive_id,public_seq)");
                ProductionSocialMigrationMain.applyPending(connection, plan, ProductionSocialMigrationMain.pendingEntries(connection, plan));
                ProductionSocialMigrationMain.validateSchema(connection, plan);
                assertEquals(List.of(List.of("1"), List.of("NULL"), List.of("2")),
                        ClosureMySqlMigrationTest.rows(connection,
                                "SELECT COALESCE(CAST(public_seq AS CHAR),'NULL') FROM game_events ORDER BY seq"));
                assertTrue(ProductionSocialMigrationMain.pendingEntries(connection, plan).isEmpty());
            }
        }
        evidence("public-event-cursor-migration", java.util.Map.of("partialDdlResume", true,
                "privateCursorAbsent", true, "publicCursorContiguous", true));
    }

    private void verifyMissingColumnAndIndexAreRejected(Connection connection, ProductionSocialMigrationMain.MigrationPlan plan) throws Exception {
        try (var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE ai_turn_jobs DROP COLUMN diagnostics");
            assertThrows(IllegalStateException.class, () -> ProductionSocialMigrationMain.validateSchema(connection, plan));
            statement.execute("ALTER TABLE ai_turn_jobs ADD COLUMN diagnostics LONGTEXT NULL");
            statement.execute("DROP INDEX idx_rooms_game_status_created ON rooms");
            assertThrows(IllegalStateException.class, () -> ProductionSocialMigrationMain.validateSchema(connection, plan));
            statement.execute("CREATE INDEX idx_rooms_game_status_created ON rooms(status,game_id,created_at)");
            assertThrows(IllegalStateException.class, () -> ProductionSocialMigrationMain.validateSchema(connection, plan));
            statement.execute("DROP INDEX idx_rooms_game_status_created ON rooms");
            statement.execute("CREATE INDEX idx_rooms_game_status_created ON rooms(game_id,status,created_at)");
            statement.execute("DROP INDEX idx_ai_trace_instance ON ai_decision_traces");
            assertThrows(IllegalStateException.class, () -> ProductionSocialMigrationMain.validateSchema(connection, plan));
            statement.execute("CREATE INDEX idx_ai_trace_instance ON ai_decision_traces(fallback,instance_id,id)");
            assertThrows(IllegalStateException.class, () -> ProductionSocialMigrationMain.validateSchema(connection, plan));
            statement.execute("DROP INDEX idx_ai_trace_instance ON ai_decision_traces");
            statement.execute("CREATE INDEX idx_ai_trace_instance ON ai_decision_traces(instance_id,fallback,id)");
            statement.execute("DROP INDEX uk_game_events_archive_public_seq ON game_events");
            assertThrows(IllegalStateException.class, () -> ProductionSocialMigrationMain.validateSchema(connection, plan));
            statement.execute("CREATE UNIQUE INDEX uk_game_events_archive_public_seq ON game_events(public_seq,archive_id)");
            assertThrows(IllegalStateException.class, () -> ProductionSocialMigrationMain.validateSchema(connection, plan));
            statement.execute("DROP INDEX uk_game_events_archive_public_seq ON game_events");
            statement.execute("CREATE UNIQUE INDEX uk_game_events_archive_public_seq ON game_events(archive_id,public_seq)");
        }
        ProductionSocialMigrationMain.validateSchema(connection, plan);
    }
}
