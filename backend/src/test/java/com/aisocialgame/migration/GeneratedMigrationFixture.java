package com.aisocialgame.migration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Tests execute the same generated plan and SQL bytes that are shipped. */
final class GeneratedMigrationFixture {
    final Path output;
    final JsonNode ledger;

    GeneratedMigrationFixture(Path directory) throws Exception {
        output = directory.resolve("migrations");
        Path repository = Path.of("..").toAbsolutePath().normalize();
        String python = System.getProperty("os.name").startsWith("Windows") ? "python.exe" : "python3";
        var process = new ProcessBuilder(python, repository.resolve("scripts/ci/write-production-sql-ledger.py").toString(),
                repository.toString(), output.toString()).inheritIO().start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("Release plan generation timed out");
        }
        if (process.exitValue() != 0) throw new IllegalStateException("Release plan generation failed");
        ledger = new ObjectMapper().readTree(output.resolve("sql-ledger.json").toFile());
    }

    List<Path> freshScripts() {
        var scripts = new ArrayList<Path>();
        for (var ordinal : ledger.path("execution_plans").get(1).path("ordinals")) {
            for (var entry : ledger.path("entries")) {
                if (entry.path("ordinal").asInt() == ordinal.asInt()) {
                    scripts.add(output.resolve("sql").resolve(Path.of(entry.path("path").asText()).getFileName()));
                }
            }
        }
        return List.copyOf(scripts);
    }
}
