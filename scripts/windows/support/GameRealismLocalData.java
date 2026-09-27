import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Explicit, local-only schema adapter. Never starts application services. */
class GameRealismLocalData {
    public static void main(String[] args) throws Exception {
        if (args.length != 4 || !Set.of("inspect", "migrate").contains(args[0])) throw new IllegalArgumentException("Use the verified PowerShell migration entry.");
        Map<String, String> env = readEnvironment(Path.of(args[1]));
        for (String key : List.of("ENV", "APP_ENV", "SPRING_PROFILES_ACTIVE")) {
            if (env.containsKey(key) && !env.get(key).equals("local")) throw new IllegalArgumentException("Only local environment is supported");
        }
        // Supplied only after the shared matrix/runtime resolver succeeds; no default or port fallback.
        String url = args[3];
        if (!url.matches("jdbc:mysql://localbase\\.testhut\\.top:[0-9]{1,5}/aisocialgame\\?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&connectTimeout=10000&socketTimeout=30000")) throw new IllegalArgumentException("Unverified local target");
        try (Connection connection = DriverManager.getConnection(url, required(env, "SPRING_DATASOURCE_USERNAME"), required(env, "SPRING_DATASOURCE_PASSWORD"))) {
            System.out.println("Local ai-social-game schema: " + connection.getCatalog());
            if (!"aisocialgame".equals(connection.getCatalog())) throw new IllegalStateException("Unexpected database");
            printColumns(connection);
            if (args[0].equals("inspect")) { System.out.println("Inspection only; pass -Apply to run the additive v2 migration."); return; }
            String sql = Files.readString(Path.of(args[2]), StandardCharsets.UTF_8);
            for (String statement : statements(sql)) try (Statement query = connection.createStatement()) { query.execute(statement); }
            printColumns(connection);
            System.out.println("Game realism v2 migration completed.");
        } catch (SQLException error) {
            System.err.println("Local migration database error: SQLState=" + error.getSQLState() + " code=" + error.getErrorCode());
            System.exit(1);
        }
    }
    static Map<String, String> readEnvironment(Path path) throws Exception {
        Map<String, String> values = new HashMap<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.stripLeading().startsWith("#")) continue;
            int split = line.indexOf('='); if (split < 1) throw new IllegalArgumentException("Invalid private environment file");
            String key = line.substring(0, split).strip().replaceFirst("^export\\s+", ""); String value = line.substring(split + 1);
            if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'")))) value = value.substring(1, value.length() - 1);
            if (values.putIfAbsent(key, value) != null) throw new IllegalArgumentException("Duplicate environment key");
        }
        return values;
    }
    static String required(Map<String, String> values, String key) {
        String value = values.get(key); if (value == null || value.isBlank()) throw new IllegalArgumentException("Required configuration missing: " + key); return value;
    }
    static List<String> statements(String sql) {
        List<String> result = new ArrayList<>(); StringBuilder current = new StringBuilder(); char quote = 0;
        for (String line : sql.split("\\R")) {
            if (line.stripLeading().startsWith("--")) continue;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (quote != 0) {
                    current.append(c);
                    if (c == quote) { if (i + 1 < line.length() && line.charAt(i + 1) == quote) current.append(line.charAt(++i)); else quote = 0; }
                } else if (c == '\'' || c == '"' || c == '`') { quote = c; current.append(c); }
                else if (c == ';') { if (!current.toString().isBlank()) result.add(current.toString().strip()); current.setLength(0); }
                else current.append(c);
            }
            current.append('\n');
        }
        if (quote != 0 || !current.toString().isBlank()) throw new IllegalArgumentException("Incomplete SQL statement");
        return result;
    }
    static void printColumns(Connection connection) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("SELECT table_name,column_name FROM information_schema.columns WHERE table_schema=DATABASE() AND ((table_name='rooms' AND column_name IN ('host_user_id','private_config')) OR (table_name='game_states' AND column_name='version') OR (table_name='ai_persona_memories' AND column_name IN ('approved_summary','review_status')) OR table_name IN ('ai_turn_jobs','ai_call_budgets')) ORDER BY table_name,ordinal_position"); ResultSet rows = query.executeQuery()) {
            while (rows.next()) System.out.println(rows.getString(1) + "." + rows.getString(2));
        }
    }
}
