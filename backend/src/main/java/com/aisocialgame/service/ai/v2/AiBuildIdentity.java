package com.aisocialgame.service.ai.v2;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;

/** Build-time resource only. Missing legacy provenance stays unknown; never trust caller-supplied IDs. */
public final class AiBuildIdentity {
    private AiBuildIdentity() {}
    private static final Map<String, String> VALUE = read();
    private static Map<String, String> read() {
        try (var stream = AiBuildIdentity.class.getResourceAsStream("/closure-build.json")) {
            return parse(stream);
        } catch (Exception ignored) { return Map.of(); }
    }
    static Map<String, String> parse(java.io.InputStream stream) {
        try {
            if (stream == null) return Map.of();
            var node = new ObjectMapper().readTree(stream);
            String build = node.path("buildId").asText(), source = node.path("sourceFingerprint").asText();
            return build.matches("[a-f0-9]{64}") && source.matches("[a-f0-9]{64}")
                    ? Map.of("buildId", build, "sourceFingerprint", source) : Map.of();
        } catch (Exception ignored) { return Map.of(); }
    }
    public static Map<String, String> current() { return VALUE; }
}
