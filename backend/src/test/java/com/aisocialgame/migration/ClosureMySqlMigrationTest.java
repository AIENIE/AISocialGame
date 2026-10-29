package com.aisocialgame.migration;

import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import static com.aisocialgame.migration.ClosureMySqlSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Current bootstrap schema for isolated native database acceptance, without a deployment executor. */
@EnabledIfEnvironmentVariable(named="AIENIE_CLOSURE_MYSQL",matches="1")
class ClosureMySqlMigrationTest {
    static void script(Connection connection,String path) {
        ScriptUtils.executeSqlScript(connection,new EncodedResource(new FileSystemResource(path),StandardCharsets.UTF_8));
    }
    @Test void currentSchemaIsRepeatableAndInitializesTheNativeBehaviorFixtures() throws Exception {
        try(var server=connect(null);var statement=server.createStatement()) {
            for(String suffix:List.of("fresh","runtime","admission")) statement.execute("CREATE DATABASE `"+database(suffix)+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }
        for(String suffix:List.of("fresh","runtime","admission")) try(var connection=connect(suffix)) {
            script(connection,"sql/schema.sql");
            var before=columns(connection);script(connection,"sql/schema.sql");
            assertEquals(before,columns(connection));
            try(var statement=connection.createStatement();var result=statement.executeQuery("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()")) {
                assertTrue(result.next());assertEquals(51,result.getInt(1));
            }
        }
        evidence("current-schema",Map.of("tables",51,"repeatable",true,"automaticMigrationLedger",false));
    }
    private static List<List<String>> columns(Connection connection) throws SQLException {
        List<List<String>> rows=new ArrayList<>();
        try(var statement=connection.createStatement();var result=statement.executeQuery("SELECT table_name,column_name,column_type,is_nullable,COALESCE(column_default,'NULL') FROM information_schema.columns WHERE table_schema=DATABASE() ORDER BY table_name,column_name")) {
            while(result.next())rows.add(List.of(result.getString(1),result.getString(2),result.getString(3),result.getString(4),result.getString(5)));
        }
        return rows;
    }
}
