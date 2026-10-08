package com.aisocialgame.migration;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** One-shot production migration entry point; never starts the web service. */
public final class ProductionSocialMigrationMain {

    private static final String COMPONENT = "ai-social-game";
    private static final String CHECKPOINT = "/run/aienie/migration-restore/checkpoint.sql";
    private static final String LEDGER = "/app/release/migrations/sql-ledger.json";
    private static final String PLAN = "/app/release/migrations/production-plan.json";
    private static final String HISTORY_TABLE = "aienie_sql_migration_ledger";
    private static final String MYSQL_PARAMS =
            "useUnicode=true&characterEncoding=utf8&serverTimezone=UTC"
                    + "&sslMode=VERIFY_IDENTITY&allowPublicKeyRetrieval=false";
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9_]{1,64}");
    private static final Pattern PLAN_ID = Pattern.compile("[a-z][a-z0-9-]{0,63}");
    private static final Pattern DIGEST = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final Pattern CREATE_TABLE = Pattern.compile(
            "(?i)CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?`?([A-Za-z0-9_]{1,64})`?");
    private static final Pattern NUMERIC_LITERAL = Pattern.compile(
            "[-+]?(?:[0-9]+(?:\\.[0-9]+)?|\\.[0-9]+)(?:[eE][-+]?[0-9]+)?");
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private ProductionSocialMigrationMain() {
    }

    public static void main(String[] args) {
        com.aienie.configpair.RuntimeConfiguration.initialize(args);
        PrintStream receiptOutput = System.out;
        System.setOut(System.err);
        try {
            if (args.length != 1 || !Set.of("checkpoint", "precheck", "execute", "reconcile").contains(args[0])) {
                throw new IllegalArgumentException("invalid action");
            }
            String action = args[0];
            try (Connection connection = openConnection()) {
                if ("checkpoint".equals(action)) {
                    writeCheckpoint(connection, Path.of(CHECKPOINT));
                    receiptOutput.print(receipt(
                            action, "checkpoint-created", "none", "none", 0, 0, 0, false));
                    return;
                }
                MigrationPlan migration = loadPlan();
                boolean readOnly = "precheck".equals(action) || "reconcile".equals(action);
                if (readOnly) {
                    beginReadOnly(connection);
                }
                try {
                    List<MigrationEntry> pending = pendingEntries(connection, migration);
                    if ("precheck".equals(action)) {
                        if (pending.isEmpty()) {
                            validateSchema(connection, migration);
                        }
                        receiptOutput.print(receipt(
                                action, pending.isEmpty() ? "current" : "pending", migration.id(),
                                migration.checksum(), pending.size(), pending.size(), 0, false));
                        return;
                    }
                    int pendingBefore = pending.size();
                    int appliedThisInvocation = 0;
                    if ("execute".equals(action)) {
                        applyPending(connection, migration, pending);
                        appliedThisInvocation = pendingBefore;
                    }
                    List<MigrationEntry> remaining = pendingEntries(connection, migration);
                    if (!remaining.isEmpty()) {
                        throw new IllegalStateException("selected migration plan remains pending");
                    }
                    validateSchema(connection, migration);
                    receiptOutput.print(receipt(
                            action, "current", migration.id(), migration.checksum(),
                            "reconcile".equals(action) ? 0 : pendingBefore, 0,
                            appliedThisInvocation, appliedThisInvocation > 0));
                } finally {
                    if (readOnly) {
                        connection.rollback();
                    }
                }
            }
        } catch (Exception error) {
            System.err.println("AISocialGame one-shot migration rejected");
            System.exit(70);
        }
    }

    private static Connection openConnection() throws SQLException {
        String url = required("SPRING_DATASOURCE_URL");
        String username = required("SPRING_DATASOURCE_USERNAME");
        String password = required("SPRING_DATASOURCE_PASSWORD");
        String database = "aisocialgame";
        if (!("jdbc:mysql://base.seekerhut.com:13306/" + database + "?" + MYSQL_PARAMS).equals(url)) {
            throw new IllegalStateException("production database authority drifted");
        }
        Connection connection = DriverManager.getConnection(url, username, password);
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT DATABASE()")) {
            if (!result.next() || !database.equals(result.getString(1)) || result.next()) {
                throw new IllegalStateException("database identity drifted");
            }
        }
        return connection;
    }

    private static String required(String key) {
        String value = com.aienie.configpair.RuntimeConfiguration.getenv(key);
        if (value == null || value.isBlank() || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalStateException("required environment value is unavailable");
        }
        return value;
    }

    private static void beginReadOnly(Connection connection) throws SQLException {
        connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        connection.setReadOnly(true);
        connection.setAutoCommit(false);
    }

    private static MigrationPlan loadPlan() throws IOException {
        Path ledgerPath = exactEnvironmentPath("AIENIE_SOCIAL_MIGRATION_LEDGER", LEDGER);
        Path planPath = exactEnvironmentPath("AIENIE_SOCIAL_MIGRATION_PLAN", PLAN);
        byte[] ledgerRaw = Files.readAllBytes(ledgerPath);
        byte[] planRaw = Files.readAllBytes(planPath);
        JsonNode ledger = JSON.readTree(ledgerRaw);
        JsonNode plan = JSON.readTree(planRaw);
        requireFields(ledger, Set.of(
                "authorization", "canonical_component_id", "entries", "execution_plans",
                "plan_selection", "schema_version"));
        requireFields(plan, Set.of(
                "authorization", "canonical_component_id", "ledger_sha256", "schema_version",
                "selected_execution_plan", "selected_ordinals"));
        if (!"aienie-production-sql-ledger-v2".equals(ledger.path("schema_version").textValue())
                || !COMPONENT.equals(ledger.path("canonical_component_id").textValue())
                || !"sealed-candidate-file-no-auto-detection".equals(ledger.path("plan_selection").textValue())
                || !"aienie-production-sql-plan-v1".equals(plan.path("schema_version").textValue())
                || !COMPONENT.equals(plan.path("canonical_component_id").textValue())
                || !sha256(ledgerRaw).equals(plan.path("ledger_sha256").textValue())) {
            throw new IllegalStateException("migration ledger or plan identity drifted");
        }
        String selectedPlan = plan.path("selected_execution_plan").textValue();
        if (selectedPlan == null || !PLAN_ID.matcher(selectedPlan).matches()) {
            throw new IllegalStateException("selected migration plan is invalid");
        }
        List<Integer> selectedOrdinals = integerArray(plan.path("selected_ordinals"));
        List<Integer> ledgerPlanOrdinals = null;
        for (JsonNode candidate : ledger.path("execution_plans")) {
            requireFields(candidate, Set.of("id", "ordinals"));
            if (selectedPlan.equals(candidate.path("id").textValue())) {
                if (ledgerPlanOrdinals != null) {
                    throw new IllegalStateException("selected migration plan is duplicated");
                }
                ledgerPlanOrdinals = integerArray(candidate.path("ordinals"));
            }
        }
        if (!selectedOrdinals.equals(ledgerPlanOrdinals) || selectedOrdinals.isEmpty()) {
            throw new IllegalStateException("selected migration plan is outside the ledger");
        }
        Map<Integer, MigrationEntry> allEntries = new LinkedHashMap<>();
        for (JsonNode value : ledger.path("entries")) {
            requireFields(value, Set.of("kind", "ordinal", "path", "sha256"));
            int ordinal = value.path("ordinal").intValue();
            String relative = value.path("path").textValue();
            String checksum = value.path("sha256").textValue();
            if (ordinal < 1 || allEntries.containsKey(ordinal) || relative == null
                    || !relative.matches("release/migrations/sql/[A-Za-z0-9_.-]+\\.sql")
                    || !DIGEST.matcher(String.valueOf(checksum)).matches()) {
                throw new IllegalStateException("migration ledger entry is invalid");
            }
            Path sql = Path.of("/app").resolve(relative).normalize();
            Path expectedRoot = Path.of("/app/release/migrations/sql");
            if (!sql.startsWith(expectedRoot) || !Files.isRegularFile(sql) || Files.isSymbolicLink(sql)
                    || !sha256(Files.readAllBytes(sql)).equals(checksum)) {
                throw new IllegalStateException("migration SQL digest drifted");
            }
            allEntries.put(ordinal, new MigrationEntry(ordinal, sql, checksum));
        }
        List<MigrationEntry> selectedEntries = new ArrayList<>();
        for (int ordinal : selectedOrdinals) {
            MigrationEntry entry = allEntries.get(ordinal);
            if (entry == null) {
                throw new IllegalStateException("selected migration ordinal is unavailable");
            }
            selectedEntries.add(entry);
        }
        return new MigrationPlan(selectedPlan, sha256(planRaw), List.copyOf(selectedEntries), Map.copyOf(allEntries));
    }

    private static Path exactEnvironmentPath(String name, String expected) {
        String value = required(name);
        if (!expected.equals(value)) {
            throw new IllegalStateException("migration artifact path drifted");
        }
        Path path = Path.of(value);
        if (!path.isAbsolute() || !path.normalize().equals(path) || !Files.isRegularFile(path) || Files.isSymbolicLink(path)) {
            throw new IllegalStateException("migration artifact path is unsafe");
        }
        return path;
    }

    private static void requireFields(JsonNode value, Set<String> expected) {
        Set<String> observed = new TreeSet<>();
        value.fieldNames().forEachRemaining(observed::add);
        if (!value.isObject() || !observed.equals(new TreeSet<>(expected))) {
            throw new IllegalStateException("migration JSON field closure drifted");
        }
    }

    private static List<Integer> integerArray(JsonNode value) {
        if (!value.isArray()) {
            throw new IllegalStateException("migration ordinal list is invalid");
        }
        List<Integer> result = new ArrayList<>();
        for (JsonNode child : value) {
            if (!child.isIntegralNumber() || !child.canConvertToInt() || child.intValue() < 1
                    || (!result.isEmpty() && child.intValue() <= result.getLast())) {
                throw new IllegalStateException("migration ordinals must be strictly increasing");
            }
            result.add(child.intValue());
        }
        return List.copyOf(result);
    }

    private static String sha256(byte[] raw) {
        try {
            return "sha256:" + HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(raw));
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    static List<MigrationEntry> pendingEntries(Connection connection, MigrationPlan plan) throws SQLException {
        Map<Integer, String> history = new LinkedHashMap<>();
        if (tableExists(connection, HISTORY_TABLE)) {
            try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(
                    "SELECT ordinal, checksum_sha256 FROM " + HISTORY_TABLE + " ORDER BY ordinal")) {
                while (result.next()) {
                    int ordinal = result.getInt(1);
                    String checksum = result.getString(2);
                    MigrationEntry known = plan.allEntries().get(ordinal);
                    if (known == null || !known.checksum().equals(checksum) || history.put(ordinal, checksum) != null) {
                        throw new IllegalStateException("migration history drifted");
                    }
                }
            }
        }
        List<MigrationEntry> pending = new ArrayList<>();
        for (MigrationEntry entry : plan.selectedEntries()) {
            if (!history.containsKey(entry.ordinal())) {
                pending.add(entry);
            }
        }
        return List.copyOf(pending);
    }

    private static boolean tableExists(Connection connection, String table) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*) FROM information_schema.tables
                 WHERE table_schema=DATABASE() AND table_type='BASE TABLE' AND table_name=?
                """)) {
            statement.setString(1, table);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getInt(1) == 1 && !result.next();
            }
        }
    }

    static void applyPending(Connection connection, MigrationPlan plan, List<MigrationEntry> pending)
            throws SQLException {
        if (pending.isEmpty()) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS aienie_sql_migration_ledger (
                      ordinal INT NOT NULL,
                      plan_id VARCHAR(64) NOT NULL,
                      migration_path VARCHAR(255) NOT NULL,
                      checksum_sha256 CHAR(71) NOT NULL,
                      applied_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                      PRIMARY KEY (ordinal)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);
        }
        for (MigrationEntry entry : pending) {
            if (entry.ordinal() == 2) {
                applyLegacyRoomUpgrade(connection);
            } else {
                executeScript(connection, entry.path());
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO " + HISTORY_TABLE
                            + "(ordinal,plan_id,migration_path,checksum_sha256) VALUES(?,?,?,?)")) {
                statement.setInt(1, entry.ordinal());
                statement.setString(2, plan.id());
                statement.setString(3, entry.path().toString());
                statement.setString(4, entry.checksum());
                if (statement.executeUpdate() != 1) {
                    throw new IllegalStateException("migration history insert was not singular");
                }
            }
        }
    }

    /** Keep the sealed historical bytes/checksum, but ignore its known accidental final literal newline token. */
    static void executeScript(Connection connection, Path path) {
        try {
            if (path.getFileName().toString().equals("20261008_room_lifecycle.sql")) {
                try (var statement = connection.prepareStatement("SET @room_migration_now=?")) {
                    statement.setObject(1, java.time.LocalDateTime.now()); statement.execute();
                }
            }
            byte[] raw = Files.readAllBytes(path);
            org.springframework.core.io.Resource resource = new FileSystemResource(path);
            if (path.getFileName().toString().equals("20260912_game_realism_v2.sql")
                    && sha256(raw).equals("sha256:c12eb713005b82b7673ec154851c4cfadd92d30d8886ddc111a0593233c4f6cc")) {
                resource = new org.springframework.core.io.ByteArrayResource(java.util.Arrays.copyOf(raw, raw.length - 2));
            }
            ScriptUtils.executeSqlScript(connection, new EncodedResource(resource, StandardCharsets.UTF_8));
        } catch (IOException | SQLException ex) { throw new IllegalStateException("Migration SQL unavailable", ex); }
    }

    // The published 20260519 SQL is immutable and predates repeatable DDL. Resume
    // its operations individually so a crash between MySQL implicit commits is safe.
    private static void applyLegacyRoomUpgrade(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            if (!columnExists(connection, "rooms", "seat_count")) {
                statement.execute("ALTER TABLE rooms ADD COLUMN seat_count INT NOT NULL DEFAULT 0 AFTER seats");
            }
            if (!columnExists(connection, "rooms", "version")) {
                statement.execute("ALTER TABLE rooms ADD COLUMN version BIGINT NULL AFTER seat_count");
            }
            statement.execute("UPDATE rooms SET seat_count = CASE WHEN seats IS NULL OR JSON_VALID(seats)=0 THEN 0 ELSE JSON_LENGTH(seats) END");
            if (indexColumns(connection, "rooms", "idx_rooms_game_status_created").isEmpty()) {
                statement.execute("CREATE INDEX idx_rooms_game_status_created ON rooms(game_id,status,created_at)");
            }
        }
    }

    private static boolean columnExists(Connection connection, String table, String column) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=? AND column_name=?")) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (ResultSet result = statement.executeQuery()) { return result.next() && result.getInt(1) == 1; }
        }
    }

    private static List<String> indexColumns(Connection connection, String table, String index) throws SQLException {
        List<String> columns = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT column_name FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name=? AND index_name=? ORDER BY seq_in_index")) {
            statement.setString(1, table);
            statement.setString(2, index);
            try (ResultSet result = statement.executeQuery()) { while (result.next()) columns.add(result.getString(1)); }
        }
        return columns;
    }

    static void validateSchema(Connection connection, MigrationPlan plan) throws SQLException, IOException {
        SchemaContract.validate(connection, plan.allEntries().values().stream().map(MigrationEntry::path).toList());
        if (!tableExists(connection, HISTORY_TABLE)) {
            throw new IllegalStateException("migration history table is missing");
        }
    }

    record MigrationEntry(int ordinal, Path path, String checksum) {
    }

    record MigrationPlan(
            String id,
            String checksum,
            List<MigrationEntry> selectedEntries,
            Map<Integer, MigrationEntry> allEntries
    ) {
    }

    private static String receipt(
            String action,
            String status,
            String currentId,
            String checksum,
            int pendingBefore,
            int pendingAfter,
            int applied,
            boolean mutationPerformed
    ) {
        return "{\"action\":\"" + action + "\",\"applied_entry_count\":" + applied
                + ",\"canonical_component_id\":\"" + COMPONENT + "\",\"current_id\":\"" + currentId
                + "\",\"migration_checksum_sha256\":\"" + checksum
                + "\",\"mutation_performed\":" + mutationPerformed
                + ",\"pending_entry_count_after\":" + pendingAfter
                + ",\"pending_entry_count_before\":" + pendingBefore
                + ",\"schema_version\":\"aienie-production-migration-database-result-v1\",\"status\":\""
                + status + "\"}";
    }

    static void writeCheckpoint(Connection connection, Path output) throws SQLException, IOException {
        if (!output.isAbsolute() || !output.normalize().equals(output)
                || Files.exists(output) || Files.isSymbolicLink(output)) {
            throw new IllegalStateException("checkpoint path is unavailable");
        }
        Path parent = output.getParent();
        if (!Files.isDirectory(parent) || Files.isSymbolicLink(parent)) {
            throw new IllegalStateException("checkpoint directory is unavailable");
        }
        ensureNoUncapturedObjects(connection);
        connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        connection.setReadOnly(true);
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET TRANSACTION READ ONLY");
            statement.execute("START TRANSACTION WITH CONSISTENT SNAPSHOT");
        }
        Path temporary = parent.resolve(".checkpoint.sql.tmp");
        if (Files.exists(temporary) || Files.isSymbolicLink(temporary)) {
            throw new IllegalStateException("checkpoint temporary path is unavailable");
        }
        try {
            Files.createFile(temporary);
            try {
                Files.setPosixFilePermissions(temporary, EnumSet.of(
                        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            } catch (UnsupportedOperationException ignored) {
                // The production target is Linux; tests on other filesystems still use CREATE_NEW.
            }
            try (BufferedWriter writer = Files.newBufferedWriter(
                    temporary, StandardCharsets.UTF_8, StandardOpenOption.WRITE)) {
                writer.write("-- aienie-consistent-mysql-restore-point-v1\n");
                writer.write("SET NAMES utf8mb4;\nSET FOREIGN_KEY_CHECKS=0;\n");
                writer.write("SET SESSION group_concat_max_len=1048576;\n");
                writer.write("SET @aienie_tables=(SELECT GROUP_CONCAT(CONCAT('`',REPLACE(table_name,'`','``'),'`') SEPARATOR ',') FROM information_schema.tables WHERE table_schema=DATABASE() AND table_type='BASE TABLE');\n");
                writer.write("SET @aienie_drop=IF(@aienie_tables IS NULL,'SELECT 1',CONCAT('DROP TABLE ',@aienie_tables));\n");
                writer.write("PREPARE aienie_drop_statement FROM @aienie_drop;\nEXECUTE aienie_drop_statement;\nDEALLOCATE PREPARE aienie_drop_statement;\n");
                for (String table : tableNames(connection)) {
                    writeTable(connection, writer, table);
                }
                writer.write("SET FOREIGN_KEY_CHECKS=1;\n");
            }
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.READ)) {
                channel.force(true);
            }
            Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE);
            try (FileChannel directory = FileChannel.open(parent, StandardOpenOption.READ)) {
                directory.force(true);
            }
        } finally {
            connection.rollback();
            Files.deleteIfExists(temporary);
        }
    }

    private static void ensureNoUncapturedObjects(Connection connection) throws SQLException {
        String sql = """
                SELECT
                  (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_type<>'BASE TABLE')
                + (SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema=DATABASE())
                + (SELECT COUNT(*) FROM information_schema.routines WHERE routine_schema=DATABASE())
                + (SELECT COUNT(*) FROM information_schema.events WHERE event_schema=DATABASE())
                """;
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            if (!result.next() || result.getLong(1) != 0 || result.next()) {
                throw new IllegalStateException("database contains uncaptured schema objects");
            }
        }
    }

    private static List<String> tableNames(Connection connection) throws SQLException {
        List<String> values = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("""
                SELECT table_name, engine FROM information_schema.tables
                 WHERE table_schema=DATABASE() AND table_type='BASE TABLE' ORDER BY table_name
                """)) {
            while (result.next()) {
                String table = result.getString(1);
                String engine = result.getString(2);
                if (!IDENTIFIER.matcher(table).matches() || !"InnoDB".equalsIgnoreCase(engine)) {
                    throw new IllegalStateException("database table is outside checkpoint policy");
                }
                values.add(table);
            }
        }
        return values;
    }

    private static void writeTable(Connection connection, BufferedWriter writer, String table)
            throws SQLException, IOException {
        String quoted = "`" + table + "`";
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SHOW CREATE TABLE " + quoted)) {
            if (!result.next()) {
                throw new IllegalStateException("table DDL is unavailable");
            }
            writer.write("DROP TABLE IF EXISTS " + quoted + ";\n");
            writer.write(result.getString(2));
            writer.write(";\n");
        }
        List<String> columns = insertableColumns(connection, table);
        if (columns.isEmpty()) {
            return;
        }
        String select = "SELECT " + columns.stream().map(value -> "`" + value + "`").reduce((a, b) -> a + "," + b).orElseThrow()
                + " FROM " + quoted;
        try (Statement statement = connection.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
            statement.setFetchSize(Integer.MIN_VALUE);
            try (ResultSet result = statement.executeQuery(select)) {
                ResultSetMetaData metadata = result.getMetaData();
                while (result.next()) {
                    writer.write("INSERT INTO " + quoted + " (");
                    writer.write(columns.stream().map(value -> "`" + value + "`").reduce((a, b) -> a + "," + b).orElseThrow());
                    writer.write(") VALUES (");
                    for (int index = 1; index <= columns.size(); index++) {
                        if (index > 1) {
                            writer.write(',');
                        }
                        writeValue(result, metadata.getColumnType(index), index, writer);
                    }
                    writer.write(");\n");
                }
            }
        }
    }

    private static void writeValue(ResultSet result, int jdbcType, int index, BufferedWriter writer)
            throws SQLException, IOException {
        switch (jdbcType) {
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT,
                    Types.REAL, Types.FLOAT, Types.DOUBLE, Types.NUMERIC, Types.DECIMAL -> {
                String value = result.getString(index);
                if (result.wasNull()) {
                    writer.write("NULL");
                } else if (!NUMERIC_LITERAL.matcher(value).matches()) {
                    throw new IllegalStateException("numeric checkpoint value is outside SQL literal policy");
                } else {
                    writer.write(value);
                }
            }
            case Types.BOOLEAN -> {
                boolean value = result.getBoolean(index);
                writer.write(result.wasNull() ? "NULL" : value ? "1" : "0");
            }
            case Types.BIT, Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB ->
                    writeHexBytes(result, index, writer, false);
            case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR,
                    Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR,
                    Types.CLOB, Types.NCLOB,
                    Types.DATE, Types.TIME, Types.TIME_WITH_TIMEZONE,
                    Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE ->
                    writeHexBytes(result, index, writer, true);
            default -> throw new IllegalStateException("checkpoint column type is outside restore policy");
        }
    }

    private static void writeHexBytes(ResultSet result, int index, BufferedWriter writer, boolean decodeUtf8)
            throws SQLException, IOException {
        byte[] value = result.getBytes(index);
        if (result.wasNull()) {
            writer.write("NULL");
            return;
        }
        if (decodeUtf8) {
            writer.write("CONVERT(");
        }
        writer.write("X'");
        writer.write(HexFormat.of().formatHex(value));
        writer.write('\'');
        if (decodeUtf8) {
            writer.write(" USING utf8mb4)");
        }
    }

    private static List<String> insertableColumns(Connection connection, String table) throws SQLException {
        List<String> columns = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT column_name FROM information_schema.columns
                 WHERE table_schema=DATABASE() AND table_name=?
                   AND extra NOT LIKE '%GENERATED%'
                 ORDER BY ordinal_position
                """)) {
            statement.setString(1, table);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    String value = result.getString(1);
                    if (!IDENTIFIER.matcher(value).matches()) {
                        throw new IllegalStateException("database column is outside checkpoint policy");
                    }
                    columns.add(value);
                }
            }
        }
        return columns;
    }
}
