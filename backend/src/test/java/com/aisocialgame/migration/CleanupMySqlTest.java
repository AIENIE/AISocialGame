package com.aisocialgame.migration;

import java.nio.file.Path;
import java.sql.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="AIENIE_CLOSURE_MYSQL", matches="1")
class CleanupMySqlTest {
    @Test void retiresAllocationColumnsIdempotentlyWithoutChangingMoneyOrReceipts() throws Exception {
        try (var connection = ClosureMySqlSupport.connect(null); var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE `" + ClosureMySqlSupport.database("cleanup") + "`");
        }
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
}
