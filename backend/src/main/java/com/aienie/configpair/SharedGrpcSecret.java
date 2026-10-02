package com.aienie.configpair;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** The environment-wide key used only for public-service gRPC authentication. */
public final class SharedGrpcSecret {
    private SharedGrpcSecret() { }
    public static String require(String value) {
        if (value == null || value.getBytes(StandardCharsets.UTF_8).length < 32
                || value.getBytes(StandardCharsets.UTF_8).length > 4096
                || value.codePoints().distinct().count() < 8
                || value.codePoints().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c)))
            throw new IllegalStateException("A strong environment gRPC shared secret is required");
        String normalized=value.toLowerCase(Locale.ROOT);
        for (String marker : java.util.List.of("${", "replace", "change-me", "change_me", "changeme", "placeholder", "example", "fixture", "dummy", "password", "sample", "<", ">"))
            if (normalized.contains(marker)) throw new IllegalStateException("The gRPC shared secret must not be a placeholder");
        return value;
    }
    public static void requireIsolated(String shared, String... otherSecrets) {
        require(shared);
        for (String other : otherSecrets)
            if (shared.equals(other)) throw new IllegalStateException("Public gRPC and HTTP/admin signing secrets must be independent");
    }
}
