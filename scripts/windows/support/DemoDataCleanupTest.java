import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

/** Isolated H2 acceptance of the offline SQL tool, never connects to a runtime DB. */
public class DemoDataCleanupTest {
    static Path manifest;
    static int checks;
    static void check(boolean condition,String message) { checks++; if(!condition) throw new AssertionError(message); }
    static long count(Connection db,String table) throws Exception { try(var statement=db.createStatement();var result=statement.executeQuery("SELECT COUNT(*) FROM "+table)) { result.next();return result.getLong(1); } }
    static void sql(Connection db,String statement) throws Exception { try(var query=db.createStatement()){query.execute(statement);} }
    static Connection fixture(String name) throws Exception {
        var db=DriverManager.getConnection("jdbc:h2:mem:"+name+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        Map<String,Map<String,String>> tables=new HashMap<>();
        List<JsonNode> entries=new ArrayList<>();DemoDataCleanup.JSON.readTree(manifest.toFile()).forEach(entries::add);
        for(var entry:entries) {
            var fields=tables.computeIfAbsent(entry.get("table").asText(),key->new LinkedHashMap<>());
            for(var node:List.of(entry.get("match"),entry.get("expected"))) node.properties().forEach(field -> fields.put(field.getKey(),field.getValue().isBoolean()?"BOOLEAN":field.getValue().isIntegralNumber()?"BIGINT":field.getValue().isFloatingPointNumber()?"DOUBLE":"VARCHAR(12000)"));
            fields.putIfAbsent("id",entry.get("table").asText().equals("rooms") || entry.get("table").asText().equals("community_posts") || entry.get("table").asText().equals("player_stats")?"VARCHAR(120)":"BIGINT");
            fields.put("created_at","TIMESTAMP");fields.put("updated_at","TIMESTAMP");
        }
        tables.get("credit_redeem_codes").put("active","BOOLEAN");tables.get("credit_redeem_codes").put("redeemed_count","INT");
        for(var table:tables.entrySet()) { List<String> fields=new ArrayList<>();table.getValue().forEach((key,type)->fields.add("`"+key+"` "+type+(key.equals("id")?" PRIMARY KEY":"")));sql(db,"CREATE TABLE "+table.getKey()+" ("+String.join(",",fields)+")"); }
        long nextId=1;
        for(var entry:entries) {
            Map<String,Object> row=new LinkedHashMap<>();
            for(var node:List.of(entry.get("match"),entry.get("expected"))) node.properties().forEach(field -> { var value=field.getValue();row.put(field.getKey(),value.isContainerNode()?value.toString():DemoDataCleanup.JSON.convertValue(value,Object.class)); });
            row.putIfAbsent("id",nextId++);row.put("created_at",Timestamp.valueOf("2026-01-01 00:00:00"));row.put("updated_at",row.get("created_at"));
            if(entry.get("table").asText().equals("credit_redeem_codes")){row.put("active",true);row.put("redeemed_count",0);}
            try(var statement=db.prepareStatement("INSERT INTO "+entry.get("table").asText()+" (`"+String.join("`,`",row.keySet())+"`) VALUES ("+String.join(",",Collections.nCopies(row.size(),"?"))+")")) { int index=1;for(var value:row.values())statement.setObject(index++,value);statement.executeUpdate(); }
        }
        sql(db,"CREATE TABLE community_likes (post_id VARCHAR(120),user_id VARCHAR(120))");
        sql(db,"CREATE TABLE game_states (room_id VARCHAR(120) PRIMARY KEY, game_id VARCHAR(64), players VARCHAR(12000))");
        sql(db,"CREATE TABLE credit_redemption_records (id INT PRIMARY KEY,code VARCHAR(64))");
        sql(db,"CREATE TABLE credit_ledger_entries (id INT PRIMARY KEY,metadata_json VARCHAR(12000),balance_permanent BIGINT)");
        sql(db,"CREATE TABLE credit_accounts (id INT PRIMARY KEY,balance BIGINT)");sql(db,"INSERT INTO credit_accounts VALUES(1,9876)");
        return db;
    }
    public static class RejectDelete implements org.h2.api.Trigger {
        public void fire(Connection db,Object[] before,Object[] after) throws SQLException { throw new SQLException("simulated delete failure","45000"); }
    }
    public static void main(String[] args) throws Exception {
        manifest=Path.of(args[0]);
        try(var db=fixture("clean")) {
            var tool=new DemoDataCleanup(db,manifest);
            var preview=tool.run(false);check(preview.plannedDeletes()==16 && preview.deleted()==0,"all original seed candidates recognized");check(count(db,"community_posts")==3,"dry-run changes nothing");
            var applied=tool.run(true);check(applied.deleted()==16,"exact seeds deleted");check(count(db,"rooms")==0,"seed rooms removed");check(count(db,"credit_accounts")==1,"balance account preserved");check(tool.run(true).records().isEmpty(),"repeat apply idempotent");
        }
        try(var db=fixture("mixed")) {
            sql(db,"INSERT INTO community_likes VALUES('demo-post-undercover','real-user')");
            sql(db,"UPDATE rooms SET seats='[{\"playerId\":\"real-user\",\"personaId\":\"ai1\"}]' WHERE id='demo-undercover-room'");
            sql(db,"UPDATE player_stats SET games_played=43 WHERE id='demo-rank-luna-total:total'");
            sql(db,"UPDATE ai_decision_traces SET confidence=0.1 WHERE action='VOTE'");
            sql(db,"UPDATE ai_persona_memories SET review_status='APPROVED' WHERE persona_id='ai3'");
            sql(db,"UPDATE credit_redeem_codes SET redeemed_count=1 WHERE code='DEMO-LOCAL-1000'");
            sql(db,"INSERT INTO credit_redemption_records VALUES(1,'DEMO-LOCAL-1000')");
            sql(db,"INSERT INTO credit_ledger_entries VALUES(1,'{\"code\":\"DEMO-LOCAL-1000\"}',1000)");
            sql(db,"INSERT INTO community_posts(id,author_name,content) VALUES('demo-unrelated','real','business')");
            var report=new DemoDataCleanup(db,manifest).run(true);
            check(report.disabled()==1,"used demo code disabled");check(report.skipped()>=6,"mixed records reported");
            check(count(db,"community_likes")==1 && count(db,"credit_redemption_records")==1 && count(db,"credit_ledger_entries")==1,"associated business records retained");
            check(count(db,"community_posts")==2,"real like and neighboring demo-named post retained");check(count(db,"rooms")==1,"real player room retained");
            check(count(db,"player_stats")==1,"changed ranking retained");check(count(db,"ai_decision_traces")==2,"mixed trace scope preserved");check(count(db,"ai_persona_memories")==2,"reviewed or business-used memories preserved");
            check(new DemoDataCleanup(db,manifest).run(true).disabled()==0,"disabled code repeat apply idempotent");
            try(var statement=db.createStatement();var row=statement.executeQuery("SELECT balance FROM credit_accounts")){row.next();check(row.getLong(1)==9876,"no balance adjustment");}
        }
        try(var db=fixture("links")) {
            sql(db,"INSERT INTO game_states VALUES('demo-werewolf-room','werewolf','[]')");
            sql(db,"UPDATE ai_persona_memories SET updated_at='2026-01-02 00:00:00' WHERE persona_id='ai3'");
            var report=new DemoDataCleanup(db,manifest).run(true);check(count(db,"rooms")==1,"game association protects room");check(count(db,"ai_persona_memories")>=1,"modified timestamp protects original-content memory");check(report.skipped()>=2,"association skips reported");
        }
        try(var db=fixture("foreign")) {
            sql(db,"ALTER TABLE credit_redeem_codes ADD CONSTRAINT unique_code UNIQUE(code)");
            sql(db,"CREATE TABLE code_audit (redeem_code VARCHAR(64), FOREIGN KEY(redeem_code) REFERENCES credit_redeem_codes(code))");
            sql(db,"INSERT INTO code_audit VALUES('DEMO-LOCAL-1000')");
            var report=new DemoDataCleanup(db,manifest).run(true);check(report.disabled()==1,"non-ID foreign key protects used code");check(count(db,"code_audit")==1,"foreign audit record retained");
        }
        try(var db=fixture("rollback")) {
            sql(db,"CREATE TRIGGER reject_stats BEFORE DELETE ON player_stats FOR EACH ROW CALL 'DemoDataCleanupTest$RejectDelete'");
            try {new DemoDataCleanup(db,manifest).run(true);throw new AssertionError("expected SQL failure");}catch(SQLException expected){check(count(db,"community_posts")==3,"earlier deletes rolled back");check(count(db,"rooms")==2,"room deletion rolled back");}
        }
        System.out.println("PASS offline demo cleanup: "+checks+" checks (exact matching, dry-run, mixed protection, codes, balances, rollback, idempotency)");
    }
}
