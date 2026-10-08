package com.aisocialgame.migration;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.aisocialgame.migration.ClosureMySqlSupport.*;

@EnabledIfEnvironmentVariable(named="AIENIE_CLOSURE_MYSQL",matches="1")
class ClosureMySqlMigrationTest {
    @org.junit.jupiter.api.io.TempDir Path temporary;
    List<Path> migrations;
    static void sql(Connection c,String s)throws SQLException{try(var st=c.createStatement()){st.execute(s);}}
    static void script(Connection c,String p){ProductionSocialMigrationMain.executeScript(c, Path.of(p));}
    static List<List<String>> rows(Connection c,String query)throws SQLException{
        List<List<String>> values=new ArrayList<>();try(var st=c.createStatement();var r=st.executeQuery(query)){while(r.next()){List<String> row=new ArrayList<>();for(int i=1;i<=r.getMetaData().getColumnCount();i++)row.add(r.getString(i));values.add(row);}}return values;
    }
    static List<List<String>> structure(Connection c)throws SQLException{return rows(c,"SELECT table_name,column_name,column_type,is_nullable,column_default FROM information_schema.columns WHERE table_schema=DATABASE() ORDER BY table_name,column_name");}
    static List<List<String>> indexes(Connection c)throws SQLException{return rows(c,"SELECT table_name,index_name,non_unique,seq_in_index,column_name FROM information_schema.statistics WHERE table_schema=DATABASE() ORDER BY table_name,index_name,seq_in_index");}
    void migrate(Connection c){for(Path m:migrations)script(c,m.toString());}
    @Test void freshLegacyAndV2AreRepeatableAndPreserveHistoricalData()throws Exception{
        var generated = new GeneratedMigrationFixture(temporary);
        migrations = generated.freshScripts().stream().skip(1).toList();
        Map<String,Object> report=new LinkedHashMap<>();List<List<String>> current,currentIndexes;
        try(var server=connect(null)){
            for(String name:List.of("fresh","legacy","v2","runtime","admission"))sql(server,"CREATE DATABASE `"+database(name)+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }
        try(var c=connect("fresh")){script(c,generated.freshScripts().getFirst().toString());migrate(c);current=structure(c);currentIndexes=indexes(c);migrate(c);migrate(c);assertEquals(current,structure(c));assertEquals(currentIndexes,indexes(c));report.put("fresh",Map.of("columns",current.size(),"repeatRuns",2,"indexColumns",currentIndexes.size()));report.put("server",rows(c,"SELECT VERSION(),@@character_set_server,@@collation_server,@@transaction_isolation,@@time_zone"));}
        for(String path:List.of("legacy","v2"))try(var c=connect(path)){
            script(c,"src/test/resources/mysql/before-rules-v2.sql");
            sql(c,"INSERT INTO rooms(id,game_id,name,status,max_players,seats) VALUES('old','undercover','fixture','WAITING',4,'[{\"playerId\":\"owner\",\"host\":true}]'),('invalid','undercover','fixture','WAITING',4,'invalid json')");
            sql(c,"INSERT INTO game_states(room_id,game_id,phase,round_number,data) VALUES('old','undercover','DESCRIPTION',2,'{\"aiMemoriesV2\":{\"owner\":{\"commitments\":[\"old\"]}}}')");
            sql(c,"INSERT INTO ai_persona_memories(persona_id,game_id,role_key,memory_summary) VALUES('ai1','undercover','CIVILIAN','legacy-text')");
            sql(c,"INSERT INTO game_archives(id,room_id,game_id,room_name,players_snapshot) VALUES('archive','old','undercover','fixture','[]')");
            sql(c,"INSERT INTO game_archives(id,room_id,game_id,room_name,players_snapshot) VALUES('malformed','old','undercover','fixture','not-json'),('wrong-shape','old','undercover','fixture','{\"players\":{}}'),('identities','old','undercover','fixture','{\"players\":[{\"playerId\":\"known\"},{\"playerId\":123},{\"playerId\":null},{\"playerId\":\""+"x".repeat(65)+"\"}]}')");
            sql(c,"INSERT INTO ai_decision_traces(game_id,action,room_id,raw_output) VALUES('undercover','SPEAK','old','{\"old\":true}')");
            var before=rows(c,"SELECT seats FROM rooms ORDER BY id"); var memory=rows(c,"SELECT data FROM game_states");var trace=rows(c,"SELECT raw_output FROM ai_decision_traces");
            if(path.equals("legacy")){
                // An already-applied first DDL followed by failure must be resumable.
                sql(c,"ALTER TABLE rooms ADD COLUMN host_user_id VARCHAR(36) NULL");
                assertThrows(SQLException.class,()->sql(c,"INVALID_MIGRATION_STATEMENT"));
            }
            script(c,migrations.getFirst().toString());
            sql(c,"INSERT INTO ai_call_budgets(id,consumed) VALUES('original',74)");
            sql(c,"INSERT INTO ai_turn_jobs(id,room_id,instance_id,actor_id,kind,turn_key,observation) VALUES('old-job','old','archive','owner','SPEAK','old-turn','{\"old\":true}')");
            migrate(c);
            sql(c,"INSERT INTO ai_call_usage(id,started_epoch_ms,admitted) VALUES('old-attempt',123,TRUE)");
            var after=structure(c);migrate(c);migrate(c);assertEquals(after,structure(c));
            assertEquals(current,after,"Fresh and upgraded schema must agree: "+path);
            assertEquals(currentIndexes,indexes(c),"Indexes must match: "+path);
            assertEquals(before,rows(c,"SELECT seats FROM rooms ORDER BY id"));assertEquals(memory,rows(c,"SELECT data FROM game_states"));assertEquals(trace,rows(c,"SELECT raw_output FROM ai_decision_traces"));
            assertEquals(List.of(List.of("owner")),rows(c,"SELECT host_user_id FROM rooms WHERE id='old'"));
            assertNull(rows(c,"SELECT host_user_id FROM rooms WHERE id='invalid'").getFirst().getFirst());
            assertEquals(List.of(List.of("74")),rows(c,"SELECT consumed FROM ai_call_budgets WHERE id='original'"));
            assertNull(rows(c,"SELECT diagnostics FROM ai_turn_jobs WHERE id='old-job'").getFirst().getFirst());
            assertEquals(Arrays.asList(null,null,null),rows(c,"SELECT prompt_tokens,completion_tokens,completed_epoch_ms FROM ai_call_usage WHERE id='old-attempt'").getFirst());
            assertEquals(List.of(List.of("legacy-text")),rows(c,"SELECT memory_summary FROM ai_persona_memories"));
            assertEquals(List.of(List.of("[]")),rows(c,"SELECT players_snapshot FROM game_archives WHERE id='archive'"));
            assertEquals(List.of(List.of("identities","known")),rows(c,"SELECT archive_id,player_id FROM archive_participants"));
            assertEquals(List.of(List.of("0")),rows(c,"SELECT COUNT(*) FROM game_archives WHERE public_replay IS NOT NULL OR host_user_id IS NOT NULL"));
            report.put(path,Map.of("repeatRuns",2,"preservedBudget",74,"structureMatchesFresh",true,"historicalDataPreserved",true));
        }
        for(String name:List.of("runtime","admission"))try(var c=connect(name)){for(Path migration:generated.freshScripts())script(c,migration.toString());}
        Map<String,String> hashes=new TreeMap<>();for(Path p:migrations)hashes.put(p.getFileName().toString(),HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p))));
        report.put("sqlSha256",hashes);evidence("migration-results",report);
    }
}
