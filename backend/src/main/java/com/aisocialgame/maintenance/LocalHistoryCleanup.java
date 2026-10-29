package com.aisocialgame.maintenance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** Explicit local maintenance entry point. Never registered with Spring or a migration plan. */
public final class LocalHistoryCleanup {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TARGET = "localmysql.testhut.top:23306/aisocialgame";
    private static final Map<String,List<String>> KEYS = new LinkedHashMap<>();
    static {
        for (String table : List.of("game_events", "local_ai_debug_turns", "ai_turn_jobs", "ai_decision_traces")) KEYS.put(table,List.of("id"));
        KEYS.put("archive_participants",List.of("archive_id","player_id"));
        KEYS.put("player_settlements",List.of("archive_id","player_id"));
        KEYS.put("ai_safety_controls",List.of("id"));
        KEYS.put("ai_persona_memories",List.of("id"));
        KEYS.put("game_states",List.of("room_id"));
        for (String table : List.of("game_archives", "rooms", "credit_ledger_entries", "ai_credit_reservations")) KEYS.put(table,List.of("id"));
    }
    private static final List<String> PROTECTED = List.of("credit_accounts", "credit_temp_lots", "credit_ledger_entries",
            "ai_credit_reservations", "credit_checkin_records", "credit_exchange_transactions", "credit_redemption_records",
            "player_stats", "player_settlement_locks", "lab_experiments", "lab_matches", "lab_training_runs",
            "lab_training_cases", "lab_training_tasks", "lab_strategy_versions", "strategy_publications", "ai_safety_events");
    public record Fingerprint(int rows, String sha256) {}
    public record Plan(String target, Map<String,List<String>> ids, Map<String,List<String>> memoryActors,
                       Map<String,String> memoryStateHashes, Map<String,Fingerprint> protectedRows) {
        public boolean empty() { return ids.values().stream().allMatch(List::isEmpty) && memoryActors.isEmpty(); }
        public Map<String,Integer> counts() {
            Map<String,Integer> result = new LinkedHashMap<>(); ids.forEach((table,keys) -> result.put(table,keys.size()));
            result.put("memoryStates",memoryActors.size()); result.put("memoryActors",memoryActors.values().stream().mapToInt(List::size).sum());
            return result;
        }
    }
    public record Result(boolean alreadyApplied, Map<String,Integer> deleted, Map<String,Fingerprint> protectedRows) {}
    private LocalHistoryCleanup() {}

    public static Plan preview(Connection connection) throws Exception { return plan(connection,false); }
    private static Plan plan(Connection connection, boolean lock) throws Exception {
        Map<String,List<Map<String,String>>> tables = new LinkedHashMap<>();
        for (String table : KEYS.keySet()) tables.put(table, rows(connection,table,lock));
        for (String table : PROTECTED) if (!tables.containsKey(table)) tables.put(table,rows(connection,table,lock));
        Set<String> oldRooms = new TreeSet<>(), oldArchives = new TreeSet<>(), oldInstances = new TreeSet<>();
        Map<String,List<String>> memories = new TreeMap<>(); Map<String,String> stateHashes = new TreeMap<>();
        for (var state : tables.get("game_states")) {
            JsonNode data = JSON.readTree(state.get("data"));
            if (data.path("ruleVersion").asInt(-1) != 2) {
                oldRooms.add(state.get("room_id"));
                String instance = data.path("archiveId").asText(""); if (!instance.isBlank()) oldInstances.add(instance);
            } else {
                var actors = data.path("aiMemoriesV2"); List<String> invalid = new ArrayList<>();
                var names = actors.fieldNames();
                while (names.hasNext()) { String actor = names.next(); if (actors.get(actor).path("formatVersion").asInt(-1) != 4) invalid.add(actor); }
                if (!invalid.isEmpty()) { Collections.sort(invalid); memories.put(state.get("room_id"),invalid); stateHashes.put(state.get("room_id"),hash(state.get("data"))); }
            }
        }
        for (var archive : tables.get("game_archives")) {
            if (JSON.readTree(archive.get("players_snapshot")).path("ruleVersion").asInt(-1) != 2) oldArchives.add(archive.get("id"));
            else require(!oldRooms.contains(archive.get("room_id")),"CLEANUP_NATIVE_ARCHIVE_SHARES_RETIRED_ROOM");
        }
        oldInstances.addAll(oldArchives);
        Map<String,List<String>> ids = new LinkedHashMap<>(); KEYS.keySet().forEach(table -> ids.put(table,new ArrayList<>()));
        Set<String> oldJobs = new HashSet<>();
        for (var row : tables.get("ai_turn_jobs")) if (oldRooms.contains(row.get("room_id")) || oldInstances.contains(row.get("instance_id"))) oldJobs.add(row.get("id"));
        for (var entry : KEYS.entrySet()) for (var row : tables.get(entry.getKey())) {
            String table = entry.getKey(); boolean remove = switch (table) {
                case "game_states" -> oldRooms.contains(row.get("room_id"));
                case "rooms" -> oldRooms.contains(row.get("id"));
                case "game_archives" -> oldArchives.contains(row.get("id"));
                case "archive_participants", "player_settlements" -> oldInstances.contains(row.get("archive_id"));
                case "game_events" -> oldRooms.contains(row.get("room_id")) || oldInstances.contains(row.get("archive_id"));
                case "ai_turn_jobs" -> oldJobs.contains(row.get("id"));
                case "local_ai_debug_turns" -> oldJobs.contains(row.get("id")) || oldRooms.contains(row.get("room_id")) || oldInstances.contains(row.get("instance_id"));
                case "ai_decision_traces" -> row.get("instance_id") == null || oldRooms.contains(row.get("room_id")) || oldInstances.contains(row.get("instance_id"));
                case "ai_safety_controls" -> "ROOM".equals(row.get("scope")) && oldRooms.contains(row.get("target_key"));
                case "ai_persona_memories" -> !"GENERAL_V2".equals(row.get("role_key"));
                case "credit_ledger_entries" -> oldLedger(row);
                case "ai_credit_reservations" -> row.get("billing_quote_base64") == null || row.get("billing_quote_base64").isBlank();
                default -> throw new IllegalStateException("CLEANUP_UNKNOWN_TABLE");
            };
            if (remove) ids.get(table).add(key(table,row));
        }
        ids.values().forEach(Collections::sort);
        Set<String> erasedIds = new HashSet<>();
        for (String table : List.of("ai_turn_jobs","local_ai_debug_turns","ai_decision_traces")) erasedIds.addAll(ids.get(table));
        erasedIds.addAll(oldRooms); erasedIds.addAll(oldInstances);
        for (var match : tables.get("lab_matches")) require(!oldRooms.contains(match.get("room_id")) && !oldInstances.contains(match.get("archive_id")),"CLEANUP_LAB_MATCH_REFERENCES_RETIRED_GAME");
        for (String table : List.of("lab_training_cases","lab_training_tasks","lab_strategy_versions","strategy_publications"))
            for (var row : tables.get(table)) for (String value : row.values()) if (value != null && (value.startsWith("{") || value.startsWith("[")))
                require(!references(JSON.readTree(value),erasedIds),"CLEANUP_RETAINED_LAB_DATA_REFERENCES_CANDIDATE");
        Set<String> erasedLedger = new HashSet<>(ids.get("credit_ledger_entries"));
        for (var row : tables.get("credit_ledger_entries")) require(!erasedLedger.contains(row.get("related_entry_id")),"CLEANUP_LEDGER_REVERSAL_REFERENCES_CANDIDATE");
        for (var row : tables.get("ai_credit_reservations")) if (ids.get("ai_credit_reservations").contains(key("ai_credit_reservations",row))) {
            require(Set.of("SETTLED","USAGE_UNKNOWN").contains(row.get("state")),"CLEANUP_UNREVIEWED_LEGACY_CALL_STATE");
            require(row.get("lab_experiment_id") == null && row.get("lab_match_id") == null,"CLEANUP_LEGACY_CALL_BOUND_TO_LAB");
        }
        Map<String,Fingerprint> protectedRows = new LinkedHashMap<>();
        for (String table : PROTECTED) {
            List<String> serialized = new ArrayList<>(); Set<String> removed = new HashSet<>(ids.getOrDefault(table,List.of()));
            for (var row : tables.get(table)) {
                if (KEYS.containsKey(table) && removed.contains(key(table,row))) continue;
                Map<String,String> fields = new TreeMap<>(row);
                if (table.equals("ai_credit_reservations")) for (String obsolete : List.of("reserved_temp","reserved_permanent","temp_portions_json")) fields.remove(obsolete);
                serialized.add(JSON.writeValueAsString(fields));
            }
            Collections.sort(serialized); protectedRows.put(table,new Fingerprint(serialized.size(),hash(JSON.writeValueAsString(serialized))));
        }
        return new Plan(TARGET,ids,memories,stateHashes,protectedRows);
    }
    private static boolean oldLedger(Map<String,String> row) throws Exception {
        if (Set.of("AI_RESERVE","AI_RELEASE").contains(row.get("type"))) return true;
        if (!"CONSUME".equals(row.get("type")) || row.get("metadata_json") == null) return false;
        JsonNode metadata = JSON.readTree(row.get("metadata_json"));
        return metadata.hasNonNull("billedTokens") && !metadata.hasNonNull("billedPoints");
    }
    private static boolean references(JsonNode value, Set<String> ids) {
        if (value.isObject()) {
            var fields = value.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if (Set.of("roomId","archiveId","instanceId","jobId","sourceJobId","traceId","sourceTraceId").contains(field.getKey())
                        && field.getValue().isValueNode() && ids.contains(field.getValue().asText())) return true;
            }
        }
        for (JsonNode child : value) if (references(child,ids)) return true;
        return false;
    }
    private static String key(String table, Map<String,String> row) throws Exception {
        var keys = KEYS.get(table);
        return keys.size() == 1 ? row.get(keys.getFirst()) : JSON.writeValueAsString(keys.stream().map(row::get).toList());
    }
    private static List<Map<String,String>> rows(Connection connection, String table, boolean lock) throws SQLException {
        List<Map<String,String>> result = new ArrayList<>();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT * FROM `"+table+"`"+(lock ? " FOR UPDATE" : ""))) {
            while (rows.next()) {
                Map<String,String> row = new LinkedHashMap<>();
                for (int column=1;column<=rows.getMetaData().getColumnCount();column++) row.put(rows.getMetaData().getColumnName(column),rows.getString(column));
                result.add(row);
            }
        }
        return result;
    }
    /** The caller owns this transaction; changes are rolled back unless every invariant holds. */
    public static Result apply(Connection connection, Plan expected) throws Exception {
        require(!connection.getAutoCommit(),"CLEANUP_TRANSACTION_REQUIRED");
        Plan current = plan(connection,true);
        require(current.protectedRows().equals(expected.protectedRows()),"CLEANUP_PROTECTED_DATA_CHANGED_SINCE_PREVIEW");
        if (current.empty()) return new Result(true,current.counts(),current.protectedRows());
        require(current.equals(expected),"CLEANUP_CANDIDATES_CHANGED_SINCE_PREVIEW");
        for (var entry : current.memoryActors().entrySet()) {
            try (var select = connection.prepareStatement("SELECT data FROM game_states WHERE room_id=?")) {
                select.setString(1,entry.getKey());
                try (var result = select.executeQuery()) {
                    require(result.next(),"CLEANUP_MEMORY_STATE_MISSING");
                    ObjectNode data = (ObjectNode)JSON.readTree(result.getString(1)); ObjectNode actors = (ObjectNode)data.get("aiMemoriesV2");
                    entry.getValue().forEach(actors::remove); if (actors.isEmpty()) data.remove("aiMemoriesV2");
                    try (var update = connection.prepareStatement("UPDATE game_states SET data=? WHERE room_id=?")) {
                        update.setString(1,JSON.writeValueAsString(data)); update.setString(2,entry.getKey()); require(update.executeUpdate()==1,"CLEANUP_MEMORY_UPDATE_COUNT");
                    }
                }
            }
        }
        for (var table : KEYS.entrySet()) {
            String where = String.join(" AND ",table.getValue().stream().map(k -> "`"+k+"`=?").toList());
            try (var delete = connection.prepareStatement("DELETE FROM `"+table.getKey()+"` WHERE "+where)) {
                for (String id : current.ids().get(table.getKey())) {
                    List<String> values = table.getValue().size()==1 ? List.of(id) : JSON.readValue(id,JSON.getTypeFactory().constructCollectionType(List.class,String.class));
                    for (int i=0;i<values.size();i++) delete.setString(i+1,values.get(i));
                    require(delete.executeUpdate()==1,"CLEANUP_DELETE_COUNT_CHANGED");
                }
            }
        }
        Plan after = plan(connection,false);
        require(after.empty(),"CLEANUP_CANDIDATES_REMAIN");
        require(after.protectedRows().equals(current.protectedRows()),"CLEANUP_PROTECTED_DATA_MODIFIED");
        return new Result(false,current.counts(),after.protectedRows());
    }
    private static String hash(String value) throws Exception { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
    private static void require(boolean valid,String code) { if (!valid) throw new IllegalStateException(code); }

    public static void main(String[] args) {
        try {
            require(args.length==4 && Set.of("dry-run","apply").contains(args[0]),"CLEANUP_ARGUMENTS");
            require("local".equals(System.getenv("AISOCIAL_CLEANUP_ENV")) && "windows-local".equals(System.getenv("AISOCIAL_CLEANUP_PLANE")),"CLEANUP_LOCAL_ONLY");
            require(args[1].startsWith("jdbc:mysql://"+TARGET+"?"),"CLEANUP_LOCAL_TARGET_REQUIRED");
            Path file = Path.of(args[2]); Object report;
            try (var connection = DriverManager.getConnection(args[1],System.getenv("AISOCIAL_CLEANUP_DB_USERNAME"),System.getenv("AISOCIAL_CLEANUP_DB_PASSWORD"))) {
                require("aisocialgame".equals(connection.getCatalog()),"CLEANUP_DATABASE_IDENTITY");
                connection.setAutoCommit(false);
                try {
                    if (args[0].equals("dry-run")) {
                        Plan plan = preview(connection); report = plan; connection.rollback();
                        Files.writeString(file,JSON.writerWithDefaultPrettyPrinter().writeValueAsString(plan),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
                        System.out.println(JSON.writeValueAsString(Map.of("target",TARGET,"counts",plan.counts(),"plan",file.toString())));
                    } else {
                        Plan plan = JSON.readValue(file.toFile(),Plan.class);
                        require(TARGET.equals(plan.target()),"CLEANUP_PLAN_TARGET");
                        Result result = apply(connection,plan); connection.commit();
                        ScriptUtils.executeSqlScript(connection,new EncodedResource(new FileSystemResource(args[3]),StandardCharsets.UTF_8));
                        Path output = file.resolveSibling(file.getFileName()+".applied-"+System.currentTimeMillis()+".json");
                        Files.writeString(output,JSON.writerWithDefaultPrettyPrinter().writeValueAsString(result),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
                        report = result; System.out.println(JSON.writeValueAsString(Map.of("target",TARGET,"result",report,"report",output.toString())));
                    }
                } catch (Exception failure) { connection.rollback(); throw failure; }
            }
        } catch (Exception failure) {
            String code = failure.getMessage();
            System.err.println(code!=null && code.startsWith("CLEANUP_") ? code : "CLEANUP_FAILED_"+failure.getClass().getSimpleName());
            System.exit(1);
        }
    }
}
