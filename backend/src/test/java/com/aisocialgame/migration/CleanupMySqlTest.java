package com.aisocialgame.migration;

import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import com.aisocialgame.maintenance.LocalHistoryCleanup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="AIENIE_CLOSURE_MYSQL", matches="1")
class CleanupMySqlTest {
    @BeforeEach void emptyIsolatedDatabase() throws Exception {
        try (var connection = ClosureMySqlSupport.connect(null); var statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS `" + ClosureMySqlSupport.database("cleanup") + "`");
            statement.execute("CREATE DATABASE `" + ClosureMySqlSupport.database("cleanup") + "`");
        }
    }
    @Test void retiresAllocationColumnsIdempotentlyWithoutChangingMoneyOrReceipts() throws Exception {
        try (var connection = ClosureMySqlSupport.connect("cleanup"); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE ai_credit_reservations (id VARCHAR(36) PRIMARY KEY, state VARCHAR(32), reserved_temp BIGINT NOT NULL, reserved_permanent BIGINT NOT NULL, temp_portions_json TEXT NOT NULL, billed_points BIGINT, billing_quote_base64 TEXT)");
            statement.execute("INSERT INTO ai_credit_reservations VALUES ('money-unknown','USAGE_UNKNOWN',0,0,'[]',NULL,'current-quote')");
            statement.execute("CREATE TABLE credit_accounts (id BIGINT PRIMARY KEY, temp_balance BIGINT, permanent_balance BIGINT)");
            statement.execute("INSERT INTO credit_accounts VALUES (1,80,100)");
            var script = Path.of("sql/20261029_retire_credit_holds.sql");
            ProductionSocialMigrationMain.executeScript(connection, script);
            ProductionSocialMigrationMain.executeScript(connection, script);
            try (var result = statement.executeQuery("SELECT state,billed_points,billing_quote_base64 FROM ai_credit_reservations")) {
                assertTrue(result.next()); assertEquals("USAGE_UNKNOWN",result.getString(1));
                assertNull(result.getObject(2)); assertEquals("current-quote",result.getString(3)); assertFalse(result.next());
            }
            try (var result = statement.executeQuery("SELECT temp_balance,permanent_balance FROM credit_accounts")) {
                assertTrue(result.next()); assertEquals(80,result.getLong(1)); assertEquals(100,result.getLong(2));
            }
            try (var result = statement.executeQuery("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='ai_credit_reservations' AND COLUMN_NAME IN ('reserved_temp','reserved_permanent','temp_portions_json')")) {
                assertTrue(result.next()); assertEquals(0,result.getInt(1));
            }
            ClosureMySqlSupport.evidence("retired-credit-columns", java.util.Map.of("passed",true,"moneyUnknownRetained",1,"balancesUnchanged",true));
        }
    }

    @Test void removesOnlyOwnedHistoryAndApprovedFinanceWhileKeepingNativeDataAndFourteenMoneyUnknowns() throws Exception {
        try (var connection=ClosureMySqlSupport.connect("cleanup")) {
            ProductionSocialMigrationMain.executeScript(connection,Path.of("sql/schema.sql"));
            insert(connection,"rooms",Map.of("id","old-room","room_code","100001","game_id","undercover","status","EXPIRED"));
            insert(connection,"rooms",Map.of("id","current-room","room_code","100002","game_id","undercover","status","PLAYING"));
            insert(connection,"game_states",Map.of("room_id","old-room","game_id","undercover","phase","SETTLEMENT","data","{}"));
            String nativeData="""
                {"ruleVersion":2,"archiveId":"current-archive","aiMemoriesV2":{"retired":{"hypotheses":["old shape"]},"native":{"formatVersion":4,"hypotheses":[],"commitments":[]}},"retained":"current material"}
                """;
            insert(connection,"game_states",Map.of("room_id","current-room","game_id","undercover","phase","DESCRIPTION","data",nativeData));
            for (String prefix:List.of("old","current")) {
                insert(connection,"game_archives",Map.of("id",prefix+"-archive","room_id",prefix+"-room","game_id","undercover","players_snapshot",prefix.equals("old")?"{}":"{\"ruleVersion\":2}"));
                insert(connection,"archive_participants",Map.of("archive_id",prefix+"-archive","player_id","player"));
                insert(connection,"player_settlements",Map.of("archive_id",prefix+"-archive","player_id","player","game_id","undercover"));
                insert(connection,"game_events",Map.of("archive_id",prefix+"-archive","room_id",prefix+"-room","game_id","undercover","seq",1,"event_type","START","visibility","PUBLIC"));
                insert(connection,"ai_turn_jobs",Map.of("id",prefix+"-job","room_id",prefix+"-room","instance_id",prefix+"-archive","actor_id","actor","kind","SPEAK","turn_key",prefix,"status","SUCCEEDED"));
                insert(connection,"local_ai_debug_turns",Map.of("id",prefix+"-job","room_id",prefix+"-room","game_id","undercover","instance_id",prefix+"-archive","actor_id","actor","payload","{}"));
                insert(connection,"ai_safety_controls",Map.of("scope","ROOM","target_key",prefix+"-room","action","PAUSE_ROOM"));
            }
            insert(connection,"ai_decision_traces",Map.of("room_id","old-room","game_id","undercover","action","SPEAK"));
            insert(connection,"ai_decision_traces",Map.of("room_id","current-room","game_id","undercover","instance_id","current-archive","action","SPEAK"));
            insert(connection,"ai_persona_memories",Map.of("persona_id","persona","game_id","undercover","role_key","CIVILIAN"));
            insert(connection,"ai_persona_memories",Map.of("persona_id","persona","game_id","undercover","role_key","GENERAL_V2"));
            insert(connection,"credit_accounts",Map.of("user_id",85,"project_key","aisocialgame","temp_balance",80,"permanent_balance",100));
            insert(connection,"credit_temp_lots",Map.of("user_id",85,"project_key","aisocialgame","remaining",40));
            insert(connection,"player_stats",Map.of("id","player-undercover","player_id","player","game_id","undercover","score",120));
            ledger(connection,"hold","AI_RESERVE","{}",-100); ledger(connection,"release","AI_RELEASE","{}",100);
            ledger(connection,"tokens","CONSUME","{\"billedTokens\":99}",-99);
            ledger(connection,"money","CONSUME","{\"billedPoints\":7,\"promptTokens\":2,\"completionTokens\":1}",-7);
            for (int i=0;i<4;i++) receipt(connection,"retired-unknown-"+i,"USAGE_UNKNOWN",null);
            receipt(connection,"retired-settled","SETTLED",null);
            for (int i=0;i<14;i++) receipt(connection,"money-unknown-"+i,"USAGE_UNKNOWN","current-price-quote");
            connection.setAutoCommit(false);
            var plan=LocalHistoryCleanup.preview(connection); connection.rollback();
            assertEquals(3,plan.counts().get("credit_ledger_entries")); assertEquals(5,plan.counts().get("ai_credit_reservations"));
            assertEquals(List.of("retired"),plan.memoryActors().get("current-room"));
            try(var statement=connection.createStatement()) { statement.executeUpdate("UPDATE credit_accounts SET permanent_balance=101"); }
            assertThrows(IllegalStateException.class,()->LocalHistoryCleanup.apply(connection,plan));
            connection.rollback();
            var applied=LocalHistoryCleanup.apply(connection,plan); connection.commit();
            assertEquals(plan.protectedRows(),applied.protectedRows()); assertFalse(applied.alreadyApplied());
            var repeated=LocalHistoryCleanup.apply(connection,plan); connection.commit();
            assertTrue(repeated.alreadyApplied()); assertTrue(repeated.deleted().values().stream().allMatch(n->n==0));
            assertEquals(14,count(connection,"SELECT COUNT(*) FROM ai_credit_reservations WHERE state='USAGE_UNKNOWN'"));
            assertEquals(1,count(connection,"SELECT COUNT(*) FROM credit_ledger_entries"));
            assertEquals(100,count(connection,"SELECT permanent_balance FROM credit_accounts"));
            assertEquals(80,count(connection,"SELECT temp_balance FROM credit_accounts"));
            assertEquals(40,count(connection,"SELECT remaining FROM credit_temp_lots"));
            for(String table:List.of("rooms","game_states","game_archives","game_events","archive_participants","player_settlements","ai_turn_jobs","local_ai_debug_turns","ai_decision_traces","ai_safety_controls","ai_persona_memories"))
                assertEquals(1,count(connection,"SELECT COUNT(*) FROM "+table),table);
            assertEquals(0,count(connection,"SELECT COUNT(*) FROM game_events e LEFT JOIN game_archives a ON a.id=e.archive_id WHERE a.id IS NULL"));
            assertEquals(0,count(connection,"SELECT COUNT(*) FROM archive_participants p LEFT JOIN game_archives a ON a.id=p.archive_id WHERE a.id IS NULL"));
            try(var statement=connection.createStatement();var result=statement.executeQuery("SELECT data FROM game_states")) {
                assertTrue(result.next());var data=new com.fasterxml.jackson.databind.ObjectMapper().readTree(result.getString(1));
                assertFalse(data.path("aiMemoriesV2").has("retired")); assertEquals(4,data.path("aiMemoriesV2").path("native").path("formatVersion").asInt());
                assertEquals("current material",data.path("retained").asText());
            }
            ClosureMySqlSupport.evidence("local-history-cleanup",Map.of("passed",true,"deleted",applied.deleted(),"retainedMoneyUnknown",14,"balancesAndLotsUnchanged",true,"repeatDeleted",repeated.deleted()));
        }
    }
    private static long count(Connection connection,String sql) throws SQLException {
        try(var statement=connection.createStatement();var result=statement.executeQuery(sql)) { assertTrue(result.next());return result.getLong(1); }
    }
    private static void ledger(Connection connection,String request,String type,String metadata,long delta) throws Exception {
        insert(connection,"credit_ledger_entries",Map.of("request_id",request,"user_id",85,"project_key","aisocialgame","type",type,"metadata_json",metadata,"token_delta_permanent",delta));
    }
    private static void receipt(Connection connection,String id,String state,String quote) throws Exception {
        Map<String,Object> fields=new HashMap<>(Map.of("id",id,"identity_hash",id,"request_hash",id,"request_id",id,"project_key","aisocialgame","user_id",85,"budget_id",id,"state",state));
        if(quote!=null)fields.put("billing_quote_base64",Base64.getEncoder().encodeToString(fireflychat.ai.v1.BudgetPriceQuote.newBuilder()
                .setCurrency("CNY").setPriceVersionId(1).setMaxCost("1.00").setPointsPerCurrency(100).setMaxCreditPoints(100).setPriceSha256("a".repeat(64)).build().toByteArray()));
        insert(connection,"ai_credit_reservations",fields);
    }
    /** Complete required storage fields so the fixture exercises the current full schema. */
    private static void insert(Connection connection,String table,Map<String,?> supplied) throws Exception {
        Map<String,Object> fields=new LinkedHashMap<>(supplied);
        try(var columns=connection.getMetaData().getColumns(connection.getCatalog(),null,table,null)) {
            while(columns.next()) {
                String name=columns.getString("COLUMN_NAME");
                if(fields.containsKey(name) || columns.getInt("NULLABLE")!=DatabaseMetaData.columnNoNulls || columns.getString("COLUMN_DEF")!=null || "YES".equals(columns.getString("IS_AUTOINCREMENT")))continue;
                fields.put(name,switch(columns.getInt("DATA_TYPE")) {
                    case Types.BIGINT,Types.INTEGER,Types.SMALLINT,Types.TINYINT,Types.BIT,Types.BOOLEAN,Types.DECIMAL,Types.DOUBLE -> 0;
                    case Types.DATE -> java.sql.Date.valueOf("2026-10-01");
                    case Types.TIMESTAMP -> Timestamp.valueOf("2026-10-01 00:00:00");
                    default -> "fixture";
                });
            }
        }
        String names=String.join(",",fields.keySet().stream().map(k->"`"+k+"`").toList());
        try(var statement=connection.prepareStatement("INSERT INTO `"+table+"` ("+names+") VALUES ("+String.join(",",Collections.nCopies(fields.size(),"?"))+")")) {
            int index=1;for(Object value:fields.values())statement.setObject(index++,value);statement.executeUpdate();
        }
    }
}
