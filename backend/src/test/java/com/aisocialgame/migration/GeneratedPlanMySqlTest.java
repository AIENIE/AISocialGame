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

    @Test void localDebugUpgradeIsRepeatableAndPreservesPrivatePayloadsAndExistingData() throws Exception {
        var generated = new GeneratedMigrationFixture(temporary);
        try (var server = connect(null); var statement = server.createStatement()) {
            for (String suffix : List.of("local_debug", "local_debug_snapshot"))
                statement.execute("CREATE DATABASE `" + database(suffix) + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }
        try (var db = connect("local_debug"); var snapshot = connect("local_debug_snapshot")) {
            var scripts = generated.freshScripts();
            var increment = scripts.stream().filter(path -> path.getFileName().toString().equals("20261020_local_ai_debug.sql")).findFirst().orElseThrow();
            // The pre-feature current-structure fixture isolates this increment from unrelated frozen historical scripts.
            String current = Files.readString(Path.of("sql/schema.sql"));
            String addition = Files.readString(increment);
            int additionStart = current.indexOf(addition);
            assertTrue(additionStart >= 0);
            assertEquals(additionStart, current.lastIndexOf(addition));
            Path previousStructure = temporary.resolve("before-local-debug.sql");
            Files.writeString(previousStructure, current.substring(0, additionStart) + current.substring(additionStart + addition.length()));
            ClosureMySqlMigrationTest.script(db, previousStructure.toString());
            assertEquals(List.of(List.of("0")), ClosureMySqlMigrationTest.rows(db, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='local_ai_debug_turns'"));
            ClosureMySqlMigrationTest.sql(db, "UPDATE ai_model_catalog SET models_json='[\"preserve-this-model\"]',version=42 WHERE id=1");
            var before = ClosureMySqlMigrationTest.rows(db, "SELECT models_json,version FROM ai_model_catalog");
            ClosureMySqlMigrationTest.script(db, increment.toString());
            ClosureMySqlMigrationTest.script(snapshot, "sql/schema.sql");
            String columns = "SELECT column_name,column_type,is_nullable,column_default FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='local_ai_debug_turns' ORDER BY ordinal_position";
            String indexes = "SELECT index_name,seq_in_index,column_name,non_unique FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='local_ai_debug_turns' ORDER BY index_name,seq_in_index";
            assertEquals(ClosureMySqlMigrationTest.rows(db, columns), ClosureMySqlMigrationTest.rows(snapshot, columns));
            assertEquals(ClosureMySqlMigrationTest.rows(db, indexes), ClosureMySqlMigrationTest.rows(snapshot, indexes));
            try (var insert = db.prepareStatement("INSERT INTO local_ai_debug_turns(id,room_id,game_id,instance_id,actor_id,round_number,payload) VALUES('debug-job','room','undercover','game-1','ai-1',1,?)")) {
                insert.setString(1, "{\"attempts\":{\"0\":{\"rawOutput\":\"" + "完整原文".repeat(10000) + "\",\"cacheReadTokens\":0},\"1\":{\"state\":\"ERROR\"}}}");
                insert.executeUpdate();
            }
            var saved = ClosureMySqlMigrationTest.rows(db, "SELECT id,payload,prompt_tokens,cache_read_tokens FROM local_ai_debug_turns");
            ClosureMySqlMigrationTest.script(db, increment.toString()); ClosureMySqlMigrationTest.script(db, increment.toString());
            assertEquals(saved, ClosureMySqlMigrationTest.rows(db, "SELECT id,payload,prompt_tokens,cache_read_tokens FROM local_ai_debug_turns"));
            assertEquals(before, ClosureMySqlMigrationTest.rows(db, "SELECT models_json,version FROM ai_model_catalog"));
            evidence("local-ai-debug-migration", java.util.Map.of("currentSchemaMatches", true, "privatePayloadPreserved", true, "existingDataPreserved", true, "repeatRuns", 2));
        }
    }

    @Test void modelConfigurationMatchesCurrentSchemaAndRepeatedMigrationPreservesSavedChoices() throws Exception {
        var generated = new GeneratedMigrationFixture(temporary);
        try (var server = connect(null); var statement = server.createStatement()) {
            for (String suffix : List.of("model_config", "model_config_snapshot"))
                statement.execute("CREATE DATABASE `" + database(suffix) + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }
        try (var db = connect("model_config"); var snapshot = connect("model_config_snapshot")) {
            for (var path : generated.freshScripts()) ClosureMySqlMigrationTest.script(db,path.toString());
            ClosureMySqlMigrationTest.script(snapshot,"sql/schema.sql");
            String structure = "SELECT table_name,column_name,column_type,is_nullable,column_default FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name IN ('ai_model_catalog','ai_function_model_configs') ORDER BY table_name,column_name";
            assertEquals(ClosureMySqlMigrationTest.rows(db,structure),ClosureMySqlMigrationTest.rows(snapshot,structure));
            ClosureMySqlMigrationTest.sql(db,"UPDATE ai_model_catalog SET models_json='[{\"modelKey\":\"saved-model\",\"thinking\":{\"declared\":false,\"defaultEnabled\":null}}]',synced_at=NOW(6),version=4 WHERE id=1");
            ClosureMySqlMigrationTest.sql(db,"INSERT INTO ai_function_model_configs(function_code,model_key,thinking_mode,thinking_effort,updated_at,version) VALUES('UNDERCOVER','saved-model','ENABLED','HIGH',NOW(6),7)");
            var before = ClosureMySqlMigrationTest.rows(db,"SELECT models_json,CAST(synced_at AS CHAR),version FROM ai_model_catalog");
            var migration = generated.freshScripts().stream().filter(path -> path.getFileName().toString().equals("20261019_ai_model_config.sql")).findFirst().orElseThrow();
            ClosureMySqlMigrationTest.script(db,migration.toString()); ClosureMySqlMigrationTest.script(db,migration.toString());
            assertEquals(before,ClosureMySqlMigrationTest.rows(db,"SELECT models_json,CAST(synced_at AS CHAR),version FROM ai_model_catalog"));
            assertEquals(List.of(List.of("saved-model","ENABLED","HIGH","7")),ClosureMySqlMigrationTest.rows(db,"SELECT model_key,thinking_mode,thinking_effort,version FROM ai_function_model_configs"));
            evidence("model-config-migration",java.util.Map.of("currentSchemaMatches",true,"savedSettingsPreserved",true,"repeatRuns",2));
        }
    }

    @Test
    void roomLifecycleBackfillsExactOriginsAndNumbersAndIsRepeatable() throws Exception {
        var generated = new GeneratedMigrationFixture(temporary);
        try (var server = connect(null); var statement = server.createStatement()) {
            statement.execute("CREATE DATABASE `" + database("room_lifecycle") + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }
        try (var db = connect("room_lifecycle")) {
            String offset = java.time.ZonedDateTime.now().getOffset().getId();
            ClosureMySqlMigrationTest.sql(db, "SET time_zone='" + (offset.equals("Z") ? "+00:00" : offset) + "'");
            var scripts = generated.freshScripts();
            var migration = scripts.stream().filter(path -> path.getFileName().toString().equals("20261008_room_lifecycle.sql")).findFirst().orElseThrow().toString();
            for (var path : scripts) {
                if (path.toString().equals(migration)) break;
                ClosureMySqlMigrationTest.script(db, path.toString());
            }
            ClosureMySqlMigrationTest.sql(db, "INSERT INTO rooms(id,game_id,name,status,max_players,created_at,updated_at) VALUES"
                + "('old','undercover','old','WAITING',4,NOW()-INTERVAL 4 HOUR,NOW()),"
                + "('recent','undercover','recent','WAITING',4,NOW()-INTERVAL 1 HOUR,NOW()),"
                + "('archive','undercover','archive','WAITING',4,NOW()-INTERVAL 2 DAY,NOW()),"
                + "('settled','undercover','settled','WAITING',4,NOW()-INTERVAL 2 DAY,NOW()),"
                + "('unknown','undercover','unknown','WAITING',4,NULL,NOW()),"
                + "('playing','undercover','playing','PLAYING',4,NOW()-INTERVAL 2 DAY,NOW())");
            ClosureMySqlMigrationTest.sql(db, "INSERT INTO game_archives(id,room_id,game_id,room_name,finished_at) VALUES('finished','archive','undercover','archive',NOW()-INTERVAL 30 MINUTE)");
            ClosureMySqlMigrationTest.sql(db, "INSERT INTO game_states(room_id,game_id,phase,round_number,updated_at) VALUES('settled','undercover','SETTLEMENT',1,NOW()-INTERVAL 40 MINUTE)");
            ClosureMySqlMigrationTest.sql(db, "ALTER TABLE rooms MODIFY COLUMN status ENUM('WAITING','PLAYING') NOT NULL");
            ClosureMySqlMigrationTest.script(db, migration);
            assertEquals(List.of(List.of("archive","WAITING"),List.of("old","EXPIRED"),List.of("playing","PLAYING"),List.of("recent","WAITING"),List.of("settled","WAITING"),List.of("unknown","EXPIRED")),
                ClosureMySqlMigrationTest.rows(db, "SELECT id,status FROM rooms ORDER BY id"));
            assertEquals(List.of(List.of("1")), ClosureMySqlMigrationTest.rows(db,"SELECT COUNT(*) FROM rooms r JOIN game_archives a ON a.room_id=r.id WHERE r.waiting_since=a.finished_at"));
            assertEquals(List.of(List.of("1")), ClosureMySqlMigrationTest.rows(db,"SELECT COUNT(*) FROM rooms r JOIN game_states s ON s.room_id=r.id WHERE r.waiting_since=s.updated_at"));
            assertEquals(List.of(List.of("6","6")), ClosureMySqlMigrationTest.rows(db,"SELECT COUNT(DISTINCT room_code),COUNT(*) FROM rooms WHERE room_code REGEXP '^[1-9][0-9]{5}$'"));
            var before = ClosureMySqlMigrationTest.rows(db,"SELECT id,room_code,status,COALESCE(CAST(waiting_since AS CHAR),'NULL'),COALESCE(CAST(expired_at AS CHAR),'NULL') FROM rooms ORDER BY id");
            ClosureMySqlMigrationTest.script(db, migration);
            assertEquals(before, ClosureMySqlMigrationTest.rows(db,"SELECT id,room_code,status,COALESCE(CAST(waiting_since AS CHAR),'NULL'),COALESCE(CAST(expired_at AS CHAR),'NULL') FROM rooms ORDER BY id"));
            evidence("room-lifecycle-migration", java.util.Map.of("repeatable",true,"origins",true,"numbers",6,"expired",2));
        }
    }

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
        var selected = new java.util.ArrayList<Integer>();
        generated.ledger.path("execution_plans").get(1).path("ordinals").forEach(value -> selected.add(value.asInt()));
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
        var selected = new java.util.ArrayList<Integer>();
        generated.ledger.path("execution_plans").get(1).path("ordinals").forEach(value -> selected.add(value.asInt()));
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
