import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Offline, local-only maintenance. Does not start Spring or modify balances. */
public class DemoDataCleanup {
    static final ObjectMapper JSON = new ObjectMapper();
    static final Set<String> TARGETS = Set.of("community_posts", "rooms", "player_stats", "ai_decision_traces", "ai_persona_memories", "credit_redeem_codes");
    record Decision(String table, Object id, String action, String reason) {}
    record Candidate(String table, Map<String,Object> row, JsonNode expected) {}
    record Report(String mode, long plannedDeletes, long plannedDisables, long deleted, long disabled, long skipped, List<Decision> records) {}
    final Connection db;
    final Map<String,Set<String>> columns = new TreeMap<>();
    final List<JsonNode> manifest;

    DemoDataCleanup(Connection db, Path manifestPath) throws Exception {
        this.db = db;
        manifest = new ArrayList<>(); JSON.readTree(manifestPath.toFile()).forEach(manifest::add);
        var meta = db.getMetaData();
        try (var tables = meta.getTables(db.getCatalog(), null, "%", new String[]{"TABLE"})) {
            while (tables.next()) {
                String table = tables.getString("TABLE_NAME");
                if (!table.matches("[a-z][a-z0-9_]*")) continue;
                Set<String> names = new HashSet<>();
                try (var fields = meta.getColumns(db.getCatalog(), null, table, "%")) { while (fields.next()) names.add(fields.getString("COLUMN_NAME")); }
                columns.put(table, names);
            }
        }
        if (!columns.keySet().containsAll(TARGETS)) throw new IllegalStateException("Required cleanup schema unavailable");
        for (JsonNode entry : manifest) {
            String table = entry.path("table").asText();
            if (!TARGETS.contains(table) || !entry.path("match").isObject() || entry.path("match").isEmpty()) throw new IllegalArgumentException("Invalid seed manifest");
            for (String field : iterable(entry.path("expected").fieldNames())) identifier(table, field);
            for (String field : iterable(entry.path("match").fieldNames())) identifier(table, field);
        }
    }
    static <T> Iterable<T> iterable(Iterator<T> iterator) { return () -> iterator; }
    String identifier(String table, String column) {
        if (!columns.getOrDefault(table, Set.of()).contains(column) || !column.matches("[a-z][a-z0-9_]*")) throw new IllegalArgumentException("Unexpected schema column: " + table + "." + column);
        return "`" + column + "`";
    }
    List<Map<String,Object>> query(String sql, Object... values) throws SQLException {
        try (var statement = db.prepareStatement(sql)) {
            for (int i=0; i<values.length; i++) statement.setObject(i+1, values[i]);
            try (var rows = statement.executeQuery()) {
                List<Map<String,Object>> result = new ArrayList<>(); var meta = rows.getMetaData();
                while (rows.next()) { Map<String,Object> row = new HashMap<>(); for(int i=1;i<=meta.getColumnCount();i++) row.put(meta.getColumnLabel(i).toLowerCase(Locale.ROOT), rows.getObject(i)); result.add(row); }
                return result;
            }
        }
    }
    boolean exists(String table, String field, Object value) throws SQLException {
        if (!columns.getOrDefault(table,Set.of()).contains(field)) return false;
        return !query("SELECT 1 FROM `"+table+"` WHERE "+identifier(table,field)+"=? FOR UPDATE",value).isEmpty();
    }
    boolean related(String field, Object value, Set<String> excluding) throws SQLException {
        for (String table : columns.keySet()) if (!excluding.contains(table) && exists(table, field, value)) return true;
        return false;
    }
    boolean foreignReference(String table, Map<String,Object> row) throws SQLException {
        try (var keys = db.getMetaData().getExportedKeys(db.getCatalog(), null, table)) {
            while (keys.next()) if (exists(keys.getString("FKTABLE_NAME"), keys.getString("FKCOLUMN_NAME"), row.get(keys.getString("PKCOLUMN_NAME")))) return true;
        }
        return false;
    }
    static JsonNode node(Object value) throws Exception {
        if (value instanceof Clob clob) return JSON.readTree(clob.getSubString(1,(int)clob.length()));
        if (value instanceof byte[] bytes) return JSON.readTree(bytes);
        return JSON.readTree(String.valueOf(value));
    }
    static boolean same(Object actual, JsonNode expected) throws Exception {
        if (expected.isNull()) return actual == null;
        if (actual == null) return false;
        if (expected.isContainerNode()) return expected.equals(node(actual));
        if (expected.isBoolean()) return actual instanceof Boolean b ? b == expected.booleanValue() : actual instanceof Number n && (n.intValue()!=0)==expected.booleanValue();
        if (expected.isNumber()) return actual instanceof Number n && new java.math.BigDecimal(n.toString()).compareTo(expected.decimalValue())==0;
        return expected.asText().equals(actual.toString());
    }
    String changes(Candidate candidate) throws Exception {
        List<String> changed = new ArrayList<>();
        for (var fields = candidate.expected.properties().iterator(); fields.hasNext();) { var field=fields.next(); if(!same(candidate.row.get(field.getKey()),field.getValue())) changed.add(field.getKey()); }
        if (!candidate.table.equals("credit_redeem_codes") && candidate.row.containsKey("updated_at") && !Objects.equals(candidate.row.get("created_at"),candidate.row.get("updated_at"))) changed.add("updated_at");
        if (candidate.table.equals("rooms") && candidate.row.get("version") instanceof Number version && version.longValue()!=0) changed.add("version");
        return changed.isEmpty() ? null : "seed changed or reviewed: " + String.join(", ", changed);
    }
    static boolean contains(JsonNode node, String value) {
        if (node.isTextual()) return value.equals(node.textValue());
        if (node.isContainerNode()) { for (JsonNode child : node) if(contains(child,value)) return true; }
        return false;
    }
    boolean snapshotReference(String value, Set<String> exceptRooms) throws Exception {
        for(String table : List.of("rooms", "game_states", "game_archives")) {
            String jsonField = table.equals("rooms") ? "seats" : table.equals("game_states") ? "players" : "players_snapshot";
            if(!columns.getOrDefault(table,Set.of()).containsAll(Set.of(jsonField, "game_id"))) continue;
            for(var row: query("SELECT * FROM `"+table+"` FOR UPDATE")) {
                if(table.equals("rooms") && exceptRooms.contains(String.valueOf(row.get("id")))) continue;
                if(row.get(jsonField)!=null && contains(node(row.get(jsonField)),value)) return true;
            }
        }
        return false;
    }
    String mixed(Candidate candidate, Set<String> safeRooms, Set<Object> safeTraces) throws Exception {
        var row = candidate.row; String table=candidate.table;
        if(table.equals("credit_redeem_codes")) return null;
        if(foreignReference(table,row)) return "related foreign-key records";
        if(table.equals("community_posts") && related("post_id",row.get("id"),Set.of(table))) return "real likes or related records";
        if(table.equals("rooms") && related("room_id",row.get("id"),Set.of(table))) return "game activity or related room records";
        if(table.equals("player_stats")) {
            String player=String.valueOf(row.get("player_id"));
            if(exists("users","id",player) || related("player_id",player,Set.of(table)) || snapshotReference(player,Set.of())) return "real player participation";
        }
        if(table.equals("ai_decision_traces")) {
            if(exists("rooms","id",row.get("room_id")) || related("room_id",row.get("room_id"),Set.of(table)) || related("trace_id",row.get("id"),Set.of(table))) return "trace linked to business activity";
        }
        if(table.equals("ai_persona_memories")) {
            String persona=String.valueOf(row.get("persona_id"));
            for(var trace: query("SELECT * FROM ai_decision_traces WHERE persona_id=? AND game_id=? AND role_key=? FOR UPDATE",persona,row.get("game_id"),row.get("role_key"))) if(!safeTraces.contains(trace.get("id"))) return "real or modified trace history";
            if(snapshotReference(persona,safeRooms)) return "persona used in business rooms or archives";
            if(related("memory_id",row.get("id"),Set.of(table))) return "related memory records";
        }
        return null;
    }
    boolean codeUsed(Candidate candidate) throws Exception {
        String code=String.valueOf(candidate.row.get("code"));
        if(((Number)candidate.row.get("redeemed_count")).longValue()!=0 || exists("credit_redemption_records","code",code) || foreignReference(candidate.table,candidate.row)) return true;
        if(columns.getOrDefault("credit_ledger_entries",Set.of()).contains("metadata_json")) for(var row: query("SELECT metadata_json FROM credit_ledger_entries FOR UPDATE")) if(row.get("metadata_json")!=null && contains(node(row.get("metadata_json")),code)) return true;
        return false;
    }
    Report run(boolean apply) throws Exception {
        db.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE); db.setAutoCommit(false);
        List<Decision> decisions=new ArrayList<>(); Set<String> safeRooms=new HashSet<>(); Set<Object> safeTraces=new HashSet<>();
        try {
            List<Candidate> candidates=new ArrayList<>();
            for(JsonNode entry:manifest) {
                String table=entry.get("table").asText(); List<String> where=new ArrayList<>(); List<Object> args=new ArrayList<>();
                entry.get("match").properties().forEach(field -> { where.add(identifier(table,field.getKey())+"=?"); args.add(JSON.convertValue(field.getValue(),Object.class)); });
                for(var row:query("SELECT * FROM `"+table+"` WHERE "+String.join(" AND ",where)+" FOR UPDATE",args.toArray())) candidates.add(new Candidate(table,row,entry.get("expected")));
            }
            // Memories depend on the room/trace decisions, which are evaluated first.
            candidates.sort(Comparator.comparingInt(candidate -> candidate.table.equals("ai_persona_memories") ? 1 : 0));
            for(Candidate candidate:candidates) {
                Object id=candidate.row.get("id"); String table=candidate.table;
                String reason=changes(candidate);
                if(reason==null) reason=mixed(candidate,safeRooms,safeTraces);
                String action="skip";
                if(reason==null) {
                    if(table.equals("credit_redeem_codes") && codeUsed(candidate)) {
                        action=Boolean.TRUE.equals(candidate.row.get("active")) || candidate.row.get("active") instanceof Number n && n.intValue()!=0 ? "disable" : "keep";
                        reason="used code: preserve redemption and accounting records";
                    } else if(table.equals("credit_redeem_codes") && !Objects.equals(candidate.row.get("created_at"),candidate.row.get("updated_at"))) reason="unused code modified";
                    else { action="delete"; reason="exact original seed without business references"; }
                }
                decisions.add(new Decision(table,id,action,reason));
                if(action.equals("delete") && table.equals("rooms")) safeRooms.add(String.valueOf(id));
                if(action.equals("delete") && table.equals("ai_decision_traces")) safeTraces.add(id);
            }
            // A modified sibling trace makes the entire artificial room scope mixed.
            var traceRows=query("SELECT * FROM ai_decision_traces WHERE room_id=? FOR UPDATE","demo-ai-quality-room");
            if(traceRows.stream().anyMatch(row -> !safeTraces.contains(row.get("id")))) {
                decisions.replaceAll(decision -> decision.table.equals("ai_decision_traces") && decision.action.equals("delete") ? new Decision(decision.table,decision.id,"skip","mixed trace scope") : decision);
                // Re-evaluate original memories before any writes.
                for(Candidate candidate:candidates) if(candidate.table.equals("ai_persona_memories")) decisions.replaceAll(decision -> decision.table.equals(candidate.table) && Objects.equals(decision.id,candidate.row.get("id")) && decision.action.equals("delete") ? new Decision(decision.table,decision.id,"skip","mixed trace scope") : decision);
            }
            if(apply) for(Decision decision:decisions) if(Set.of("delete","disable").contains(decision.action)) {
                String sql=decision.action.equals("delete") ? "DELETE FROM `"+decision.table+"` WHERE id=?" : "UPDATE credit_redeem_codes SET active=false WHERE id=?";
                try(var statement=db.prepareStatement(sql)) { statement.setObject(1,decision.id); if(statement.executeUpdate()!=1) throw new IllegalStateException("Candidate changed during cleanup"); }
            }
            if(apply) db.commit(); else db.rollback();
            long deletes=decisions.stream().filter(d->d.action.equals("delete")).count(), disables=decisions.stream().filter(d->d.action.equals("disable")).count();
            return new Report(apply?"apply":"dry-run",deletes,disables,apply?deletes:0,apply?disables:0,decisions.stream().filter(d->Set.of("skip","keep").contains(d.action)).count(),decisions);
        } catch(Exception error) { db.rollback(); throw error; }
        finally { db.setAutoCommit(true); }
    }
    public static void main(String[] args) throws Exception {
        if(args.length!=3 || !Set.of("dry-run","apply").contains(args[0])) throw new IllegalArgumentException("Use Cleanup-LocalDemoData.ps1");
        if(!"local".equals(System.getenv("AISOCIAL_CLEANUP_ENV")) || !"windows-local".equals(System.getenv("AISOCIAL_CLEANUP_PLANE"))) throw new IllegalArgumentException("Local Windows identity required");
        if(!args[2].matches("jdbc:mysql://localmysql\\.testhut\\.top:[0-9]{1,5}/aisocialgame\\?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&connectTimeout=10000&socketTimeout=30000")) throw new IllegalArgumentException("Unverified local target");
        String username=System.getenv("AISOCIAL_CLEANUP_DB_USERNAME"), password=System.getenv("AISOCIAL_CLEANUP_DB_PASSWORD");
        if(username==null || password==null) throw new IllegalArgumentException("Missing database credentials");
        try(Connection db=DriverManager.getConnection(args[2],username,password)) {
            if(!"aisocialgame".equals(db.getCatalog())) throw new IllegalStateException("Unexpected database");
            try(var statement=db.prepareStatement("SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_TYPE='BASE TABLE' AND ENGINE<>'InnoDB'"); var rows=statement.executeQuery()) { if(rows.next()) throw new IllegalStateException("Transactional tables required"); }
            System.out.println(JSON.writeValueAsString(new DemoDataCleanup(db,Path.of(args[1])).run(args[0].equals("apply"))));
        } catch(SQLException error) { System.err.println("Cleanup rolled back: SQLState="+error.getSQLState()+" code="+error.getErrorCode()); System.exit(1); }
    }
}
