package com.aisocialgame.migration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Structural verification from the sealed SQL, including columns and index order/uniqueness. */
final class SchemaContract {
    private static final String IDENTIFIER = "`?([A-Za-z0-9_]+)`?";
    private static final Pattern TABLE = Pattern.compile("(?is)CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?" + IDENTIFIER + "\\s*\\((.*?)\\)\\s*(?:ENGINE[^;]*)?;");
    private static final Pattern INDEX = Pattern.compile("(?is)^(PRIMARY\\s+KEY|UNIQUE\\s+(?:KEY|INDEX)|KEY|INDEX)\\s*(?:`?([A-Za-z0-9_]+)`?\\s*)?\\(([^)]+)\\).*$");
    private static final Pattern COLUMN = Pattern.compile("(?is)^" + IDENTIFIER + "\\s+(?:CHAR|VARCHAR|TEXT|LONGTEXT|MEDIUMTEXT|INT|INTEGER|BIGINT|SMALLINT|TINYINT|BOOLEAN|BOOL|DATETIME|TIMESTAMP|DATE|DOUBLE|FLOAT|DECIMAL|VARBINARY|BINARY|JSON)\\b.*$");
    private static final Pattern ADD_COLUMN = Pattern.compile("(?i)ALTER\\s+TABLE\\s+" + IDENTIFIER + "\\s+ADD\\s+COLUMN\\s+" + IDENTIFIER);
    private static final Pattern CREATE_INDEX = Pattern.compile("(?i)CREATE\\s+(UNIQUE\\s+)?INDEX\\s+" + IDENTIFIER + "\\s+ON\\s+" + IDENTIFIER + "\\s*\\(([^)]+)\\)");

    private SchemaContract() { }

    static void validate(Connection connection, List<Path> scripts) throws IOException, SQLException {
        Map<String, Set<String>> columns = new LinkedHashMap<>();
        Map<String, Map<String, Index>> indexes = new LinkedHashMap<>();
        for (Path script : scripts) {
            String sql = Files.readString(script).replaceAll("(?m)^\\s*--[^\\r\\n]*", "");
            var tables = TABLE.matcher(sql);
            while (tables.find()) {
                String table = tables.group(1);
                var expectedColumns = columns.computeIfAbsent(table, ignored -> new LinkedHashSet<>());
                var expectedIndexes = indexes.computeIfAbsent(table, ignored -> new LinkedHashMap<>());
                for (String definition : definitions(tables.group(2))) {
                    var index = INDEX.matcher(definition);
                    var column = COLUMN.matcher(definition);
                    if (index.matches()) {
                        String kind = index.group(1).toUpperCase(Locale.ROOT);
                        String name = kind.startsWith("PRIMARY") ? "PRIMARY" : index.group(2);
                        List<String> names = definitions(index.group(3)).stream().map(value -> value.replace("`", "").trim()).toList();
                        expectedIndexes.put(name, new Index(names, kind.startsWith("PRIMARY") || kind.startsWith("UNIQUE")));
                    } else if (column.matches()) {
                        expectedColumns.add(column.group(1));
                        if (definition.toUpperCase(Locale.ROOT).contains("PRIMARY KEY")) {
                            expectedIndexes.put("PRIMARY", new Index(List.of(column.group(1)), true));
                        }
                    } else {
                        throw new IllegalStateException("unsupported SQL schema definition in " + table);
                    }
                }
            }
            var additions = ADD_COLUMN.matcher(sql);
            while (additions.find()) columns.computeIfAbsent(additions.group(1), ignored -> new LinkedHashSet<>()).add(additions.group(2));
            var createdIndexes = CREATE_INDEX.matcher(sql);
            while (createdIndexes.find()) {
                String name = createdIndexes.group(2);
                String table = createdIndexes.group(3);
                List<String> names = definitions(createdIndexes.group(4)).stream().map(value -> value.replace("`", "").trim()).toList();
                indexes.computeIfAbsent(table, ignored -> new LinkedHashMap<>())
                        .put(name, new Index(names, createdIndexes.group(1) != null));
            }
        }
        if (columns.isEmpty()) throw new IllegalStateException("sealed SQL has no schema contract");
        for (var table : columns.entrySet()) {
            Set<String> actual = new LinkedHashSet<>();
            try (var statement = connection.prepareStatement("SELECT column_name FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=?")) {
                statement.setString(1, table.getKey());
                try (var result = statement.executeQuery()) { while (result.next()) actual.add(result.getString(1)); }
            }
            if (!actual.containsAll(table.getValue())) throw new IllegalStateException("schema columns missing in " + table.getKey());
            for (var index : indexes.getOrDefault(table.getKey(), Map.of()).entrySet()) {
                List<String> actualColumns = new ArrayList<>();
                boolean unique = true;
                try (var statement = connection.prepareStatement("SELECT column_name,non_unique FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name=? AND index_name=? ORDER BY seq_in_index")) {
                    statement.setString(1, table.getKey());
                    statement.setString(2, index.getKey());
                    try (var result = statement.executeQuery()) {
                        while (result.next()) { actualColumns.add(result.getString(1)); unique &= result.getInt(2) == 0; }
                    }
                }
                if (!actualColumns.equals(index.getValue().columns()) || unique != index.getValue().unique()) {
                    throw new IllegalStateException("schema index drifted: " + table.getKey() + "." + index.getKey());
                }
            }
        }
    }

    private static List<String> definitions(String body) {
        List<String> result = new ArrayList<>();
        int depth = 0, start = 0;
        boolean quoted = false;
        for (int i = 0; i < body.length(); i++) {
            char current = body.charAt(i);
            if (current == '\'') quoted = !quoted;
            if (quoted) continue;
            if (current == '(') depth++;
            if (current == ')') depth--;
            if (current == ',' && depth == 0) { result.add(body.substring(start, i).trim()); start = i + 1; }
        }
        result.add(body.substring(start).trim());
        return result;
    }

    private record Index(List<String> columns, boolean unique) { }
}
