package com.aisocialgame.config;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/** Fail-closed contract for AISocialGame's least-privilege PayService caller identity. */
public final class PayServiceJwtConfigurationValidator {
    public static final String REQUIRED_CALLER_ID = "aisocialgame";
    public static final String REQUIRED_AUDIENCE = "aienie-payservice-grpc";
    public static final String REQUIRED_ROLE = "SERVICE";
    public static final long REQUIRED_TTL_SECONDS = 300L;
    public static final List<String> REQUIRED_SCOPES = List.of(
            "billing.balance.read",
            "billing.checkin.write",
            "billing.checkin.read",
            "billing.ledger.read",
            "billing.balance.convert",
            "billing.onboarding.write",
            "billing.redeem.write"
    );
    public static final String REQUIRED_SCOPES_VALUE = String.join(",", REQUIRED_SCOPES);

    private PayServiceJwtConfigurationValidator() {
    }

    public static void validate(AppProperties.External external) {
        if (external == null) {
            throw new IllegalArgumentException("External gRPC authentication configuration is required");
        }
        AppProperties.PayServiceJwt configuration = external.getPayserviceJwt();
        if (configuration == null) {
            throw new IllegalArgumentException("PayService caller JWT configuration is required");
        }
        requireExact(configuration.getCallerId(), REQUIRED_CALLER_ID, "caller id");
        requireExact(configuration.getIssuer(), REQUIRED_CALLER_ID, "issuer");
        requireExact(configuration.getService(), REQUIRED_CALLER_ID, "service");
        requireExact(configuration.getAudience(), REQUIRED_AUDIENCE, "audience");
        requireExact(configuration.getRole(), REQUIRED_ROLE, "role");
        if (configuration.getTtlSeconds() != REQUIRED_TTL_SECONDS) {
            throw new IllegalArgumentException("PayService caller JWT TTL must be exactly 300 seconds");
        }
        if (!REQUIRED_SCOPES_VALUE.equals(configuration.getScopes())) {
            throw new IllegalArgumentException("PayService caller JWT scopes violate least privilege");
        }
        validateSecret(configuration.getSecret());
    }

    public static void validateSecret(String secret) {
        int length = secret == null ? 0 : secret.getBytes(StandardCharsets.UTF_8).length;
        if (isPlaceholder(secret)
                || isStructurallyWeak(secret)
                || secret == null
                || secret.codePoints().anyMatch(codePoint -> Character.isWhitespace(codePoint)
                        || Character.isSpaceChar(codePoint)
                        || Character.isISOControl(codePoint))
                || length < 32
                || length > 4_096) {
            throw new IllegalArgumentException(
                    "PayService caller JWT secret must contain 32..4096 whitespace-free UTF-8 bytes");
        }
    }

    private static void requireExact(String actual, String expected, String label) {
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException("PayService caller JWT " + label + " is not canonical");
        }
    }

    private static boolean isPlaceholder(String value) {
        if (value == null) {
            return true;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return normalized.isEmpty()
                || normalized.contains("REPLACE")
                || normalized.contains("CHANGE_ME")
                || normalized.contains("CHANGE-ME")
                || normalized.contains("CHANGEME")
                || normalized.contains("PLACEHOLDER")
                || normalized.startsWith("<")
                || normalized.endsWith(">");
    }

    private static boolean isStructurallyWeak(String value) {
        if (value == null || value.chars().distinct().count() < 8L) {
            return true;
        }
        for (int period = 1; period <= value.length() / 2; period++) {
            if (value.length() % period != 0) {
                continue;
            }
            boolean repeated = true;
            for (int index = period; index < value.length(); index++) {
                if (value.charAt(index) != value.charAt(index % period)) {
                    repeated = false;
                    break;
                }
            }
            if (repeated) {
                return true;
            }
        }
        return false;
    }
}
