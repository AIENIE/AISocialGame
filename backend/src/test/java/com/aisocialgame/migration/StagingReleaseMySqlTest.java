package com.aisocialgame.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.aisocialgame.migration.ClosureMySqlSupport.*;

@EnabledIfEnvironmentVariable(named="AIENIE_CLOSURE_MYSQL",matches="1")
class StagingReleaseMySqlTest {
    private static final List<String> UPGRADE = List.of("schema.sql", "20261008_room_lifecycle.sql",
            "20261031_staging_v0_0_1.sql", "20261004_admin_emergency_codes.sql", "20261029_retire_credit_holds.sql");
    private static final String CLEANUP = "sql/releases/0.0.1/staging-history.sql";

    @Test void upgradesActualStagingStructureAndRetiresOnlyReviewedHistoryRepeatably() throws Exception {
        try (var connection=connect(null);var statement=connection.createStatement()) {
            for(String suffix:List.of("legacy","v2")) {
                statement.execute("DROP DATABASE IF EXISTS `"+database(suffix)+"`");
                statement.execute("CREATE DATABASE `"+database(suffix)+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
            }
        }
        try (var connection=connect("legacy")) {
            script(connection,"src/test/resources/migrations/staging-20261031-schema.sql");
            assertEquals(31,number(connection,"SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()"));
            insert(connection,"rooms",Map.of("id","old-room","game_id","undercover","status","WAITING"));
            insert(connection,"rooms",Map.of("id","current-room","game_id","undercover","status","PLAYING"));
            insert(connection,"game_states",Map.of("room_id","old-room","game_id","undercover","phase","DESCRIPTION","data","{}"));
            insert(connection,"game_states",Map.of("room_id","current-room","game_id","undercover","phase","DESCRIPTION","data","{\"ruleVersion\":2}"));
            insert(connection,"credit_accounts",Map.of("user_id",1,"project_key","aisocialgame","permanent_balance",1500,"temp_balance",80));
            insert(connection,"credit_ledger_entries",Map.of("request_id","old-tokens","type","CONSUME","metadata_json","{\"billedTokens\":5}"));
            insert(connection,"credit_ledger_entries",Map.of("request_id","current-money","type","CONSUME","metadata_json","{\"billedTokens\":5,\"billedPoints\":1}"));
            insert(connection,"ai_persona_memories",Map.of("persona_id","legacy-persona","game_id","undercover","role_key","UNDERCOVER"));
            insert(connection,"admin_totp_credentials",Map.of("subject_id","admin","encrypted_secret","fixture-seed","nonce","fixture"));
            insert(connection,"admin_sessions",Map.of("session_hash","full-session","subject_id","admin","scope","FULL"));
            insert(connection,"admin_sessions",Map.of("session_hash","recovery-session","subject_id","admin","scope","RECOVERY_REBIND_ONLY"));
            upgrade(connection);
            var structure=structure(connection);
            upgrade(connection);
            assertEquals(structure,structure(connection));
            assertEquals(51,number(connection,"SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()"));
            assertEquals(1,number(connection,"SELECT COUNT(*) FROM admin_totp_credentials WHERE encrypted_secret='fixture-seed'"));
            assertEquals(1,number(connection,"SELECT COUNT(*) FROM admin_sessions WHERE scope='FULL' AND revoked_at IS NULL"));
            assertEquals(1,number(connection,"SELECT COUNT(*) FROM admin_sessions WHERE scope='RECOVERY_REBIND_ONLY' AND revoked_at IS NOT NULL"));
            assertEquals(2,number(connection,"SELECT COUNT(DISTINCT room_code) FROM rooms WHERE room_code REGEXP '^[0-9]{6}$'"));
            insert(connection,"credit_temp_lots",Map.of("user_id",1,"project_key","aisocialgame","remaining",40));
            insert(connection,"ai_persona_memories",Map.of("persona_id","current-persona","game_id","undercover","role_key","GENERAL_V2"));
            for(String prefix:List.of("old","current")) {
                insert(connection,"ai_turn_jobs",Map.of("id",prefix+"-job","room_id",prefix+"-room","turn_key",prefix));
                insert(connection,"local_ai_debug_turns",Map.of("id",prefix+"-job","room_id",prefix+"-room"));
                insert(connection,"ai_decision_traces",Map.of("room_id",prefix+"-room","instance_id",prefix+"-instance"));
                insert(connection,"game_events",Map.of("room_id",prefix+"-room","archive_id",prefix+"-instance","seq",1,"visibility","PUBLIC"));
                insert(connection,"ai_safety_controls",Map.of("scope","ROOM","target_key",prefix+"-room"));
            }
            for(int i=0;i<14;i++) insert(connection,"ai_credit_reservations",Map.of("id","money-"+i,
                    "identity_hash",String.format("%064d",i),"request_id","request-"+i,"budget_id","budget-"+i,
                    "state","USAGE_UNKNOWN","billing_quote_base64","current-quote"));
            script(connection,CLEANUP);
            script(connection,CLEANUP);
            for(String table:List.of("rooms","game_states","ai_turn_jobs","local_ai_debug_turns","ai_decision_traces","game_events","ai_safety_controls","ai_persona_memories","credit_ledger_entries"))
                assertEquals(1,number(connection,"SELECT COUNT(*) FROM "+table),table);
            assertEquals(1,number(connection,"SELECT COUNT(*) FROM rooms WHERE id='current-room'"));
            assertEquals(1,number(connection,"SELECT COUNT(*) FROM credit_ledger_entries WHERE request_id='current-money'"));
            assertEquals(14,number(connection,"SELECT COUNT(*) FROM ai_credit_reservations WHERE state='USAGE_UNKNOWN' AND billing_quote_base64='current-quote'"));
            assertEquals(1500,number(connection,"SELECT permanent_balance FROM credit_accounts"));
            assertEquals(80,number(connection,"SELECT temp_balance FROM credit_accounts"));
            assertEquals(40,number(connection,"SELECT remaining FROM credit_temp_lots"));
            try(var fresh=connect("v2")) {
                script(fresh,"sql/schema.sql");
                var expected=new HashSet<>(structure(fresh));
                var actual=new HashSet<>(structure(connection));
                var missing=new HashSet<>(expected);missing.removeAll(actual);
                assertEquals(Set.of(),missing,"all current columns and indexes must exist");
                actual.removeAll(expected);
                assertEquals(Set.of(
                        List.of("rooms","idx_rooms_game_status_created","1","1","game_id","BTREE"),
                        List.of("rooms","idx_rooms_game_status_created","1","2","status","BTREE"),
                        List.of("rooms","idx_rooms_game_status_created","1","3","created_at","BTREE")),
                        actual,"retain the existing game/status/creation-time lookup index");
            }
        }
        evidence("staging-0.0.1",Map.of("passed",true,"baselineTables",31,"currentTables",51,
                "repeatable",true,"balancesAndLotsUnchanged",true,"moneyUnknownRetained",14));
    }
    private static void upgrade(Connection connection) {for(String path:UPGRADE)script(connection,"sql/"+path);}
    private static void script(Connection connection,String path) {ClosureMySqlMigrationTest.script(connection,path);}
    private static long number(Connection connection,String sql) throws SQLException {
        try(var statement=connection.createStatement();var result=statement.executeQuery(sql)) {assertTrue(result.next());return result.getLong(1);}
    }
    private static List<List<String>> structure(Connection connection) throws SQLException {
        var rows=new ArrayList<List<String>>();
        for(String sql:List.of("SELECT table_name,column_name,column_type,is_nullable,COALESCE(column_default,'<NULL>'),extra,COALESCE(collation_name,'<NULL>') FROM information_schema.columns WHERE table_schema=DATABASE() ORDER BY table_name,column_name",
                "SELECT table_name,index_name,non_unique,seq_in_index,column_name,index_type FROM information_schema.statistics WHERE table_schema=DATABASE() ORDER BY table_name,index_name,seq_in_index"))
            try(var statement=connection.createStatement();var result=statement.executeQuery(sql)) {
                while(result.next()) {var fields=new ArrayList<String>();for(int i=1;i<=result.getMetaData().getColumnCount();i++)fields.add(result.getString(i));rows.add(fields);}
            }
        return rows;
    }
    private static void insert(Connection connection,String table,Map<String,?> supplied) throws SQLException {
        Map<String,Object> fields=new LinkedHashMap<>(supplied);
        try(var columns=connection.getMetaData().getColumns(connection.getCatalog(),null,table,null)) {
            while(columns.next()) {
                String name=columns.getString("COLUMN_NAME");
                if(fields.containsKey(name)||columns.getInt("NULLABLE")!=DatabaseMetaData.columnNoNulls||columns.getString("COLUMN_DEF")!=null||"YES".equals(columns.getString("IS_AUTOINCREMENT")))continue;
                fields.put(name,switch(columns.getInt("DATA_TYPE")) {
                    case Types.BIGINT,Types.INTEGER,Types.SMALLINT,Types.TINYINT,Types.BIT,Types.BOOLEAN,Types.DOUBLE,Types.DECIMAL -> 0;
                    case Types.TIMESTAMP -> Timestamp.valueOf("2026-10-01 00:00:00");
                    default -> "fixture";
                });
            }
        }
        try(var statement=connection.prepareStatement("INSERT INTO `"+table+"` (`"+String.join("`,`",fields.keySet())+"`) VALUES ("+String.join(",",Collections.nCopies(fields.size(),"?"))+")")) {
            int index=1;for(Object value:fields.values())statement.setObject(index++,value);statement.executeUpdate();
        }
    }
}
