package com.aisocialgame.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.HashMap;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PayServiceJwtStartupGuardTest {
    private static final String LOCAL_TRUST = localTrust();
    private static final String USER_SECRET = "aisocialgame-userservice-guard-secret-32-bytes";
    private static final String PAY_SECRET = "aisocialgame-payservice-guard-secret-32-bytes";

    private static String localTrust() {
        Path root = Path.of("").toAbsolutePath();
        Path file = root.resolve("scripts/windows/local-trust/localcert-root-ca.crt");
        if (!Files.isRegularFile(file)) {
            file = root.resolve("../scripts/windows/local-trust/localcert-root-ca.crt");
        }
        return file.normalize().toUri().toString();
    }

    @Test
    void acceptsCanonicalFinalBoundConfiguration() {
        AppProperties properties = validProperties();
        assertDoesNotThrow(() -> PayServiceJwtStartupGuard.validateFinalConfiguration(
                properties, canonicalEnvironment(), new String[0], new String[]{"default"}, false));
    }

    @Test
    void acceptsOnlyExactWindowsLocalProfileSelectorsAndLoopback() {
        AppProperties properties = validProperties();
        Map<String, String> local = canonicalEnvironment();
        local.put("ENV", "local");
        local.put("APP_ENV", "local");
        local.put("AIENIE_RUNTIME_PLANE", "windows-local");
        local.put("SPRING_PROFILES_ACTIVE", "local");
        local.put("SERVER_ADDRESS", "127.0.0.1");

        assertDoesNotThrow(() -> PayServiceJwtStartupGuard.validateFinalConfiguration(
                properties, local, new String[]{"local"}, new String[]{"default"}, false,
                "Windows 11"));

        Map<String, String> wildcard = new HashMap<>(local);
        wildcard.put("SERVER_ADDRESS", "0.0.0.0");
        assertThrows(IllegalStateException.class,
                () -> PayServiceJwtStartupGuard.validateFinalConfiguration(
                        properties, wildcard, new String[]{"local"}, new String[]{"default"},
                        false, "Windows 11"));
        assertThrows(IllegalStateException.class,
                () -> PayServiceJwtStartupGuard.validateFinalConfiguration(
                        properties, local, new String[]{"local"}, new String[]{"default"},
                        false, "Linux"));
    }

    @Test
    void validatesFinalBindingBeforeServerCreation() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("app.external.grpc-auth-required", "true")
                .withProperty("app.external.userservice-jwt.secret", USER_SECRET)
                .withProperty("app.external.aiservice-hmac-caller", "aisocialgame")
                .withProperty("app.external.aiservice-hmac-secret",
                        "aisocialgame-ai-hmac-guard-secret-32-bytes")
                .withProperty("app.external.payservice-jwt.caller-id", "aisocialgame")
                .withProperty("app.external.payservice-jwt.issuer", "aisocialgame")
                .withProperty("app.external.payservice-jwt.service", "aisocialgame")
                .withProperty("app.external.payservice-jwt.secret", PAY_SECRET)
                .withProperty("app.external.payservice-jwt.audience", "aienie-payservice-grpc")
                .withProperty("app.external.payservice-jwt.role", "SERVICE")
                .withProperty("app.external.payservice-jwt.ttl-seconds", "300")
                .withProperty("app.external.payservice-jwt.scopes",
                        PayServiceJwtConfigurationValidator.REQUIRED_SCOPES_VALUE)
                .withProperty("spring.grpc.client.channel.billing.target", "static://localpayservice.testhut.top:22021")
                .withProperty("spring.grpc.client.channel.billing.ssl.enabled", "true")
                .withProperty("app.external.payservice-plaintext-enabled", "false")
                .withProperty("app.grpc.billing-trust-cert-collection", LOCAL_TRUST);

        assertDoesNotThrow(() -> PayServiceJwtStartupGuard.validateBeforeServerCreation(
                environment, canonicalEnvironmentWithTransport()));
    }

    @Test
    void rejectsFinalPayTransportOverrideAndRequiresLocalTrust() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.grpc.client.channel.billing.target", "static://localpayservice.testhut.top:22021")
                .withProperty("spring.grpc.client.channel.billing.ssl.enabled", "true")
                .withProperty("app.external.payservice-plaintext-enabled", "false")
                .withProperty("app.grpc.billing-trust-cert-collection", LOCAL_TRUST);
        Map<String, String> raw = canonicalEnvironmentWithTransport();
        assertDoesNotThrow(() -> PayServiceJwtStartupGuard.validatePayTransport(environment, raw));

        environment.setProperty(
                "app.grpc.billing-trust-cert-collection", "file:///attacker/ca.crt");
        assertThrows(IllegalStateException.class,
                () -> PayServiceJwtStartupGuard.validatePayTransport(environment, raw));

        environment.setProperty(
                "app.grpc.billing-trust-cert-collection",
                LOCAL_TRUST);
        environment.setProperty("spring.grpc.client.channel.billing.override-authority", "attacker.invalid");
        assertThrows(IllegalStateException.class,
                () -> PayServiceJwtStartupGuard.validatePayTransport(environment, raw));

        environment.setProperty("spring.grpc.client.channel.billing.target", "static://attacker.invalid:12021");
        raw.put("BILLING_GRPC_ADDR", "static://attacker.invalid:12021");
        assertThrows(IllegalStateException.class,
                () -> PayServiceJwtStartupGuard.validatePayTransport(environment, raw));
    }

    @Test
    void acceptsCanonicalTestPublicTrustAndRejectsProductionWithoutSignedPreactivationAuthority() {
        MockEnvironment testEnvironment = new MockEnvironment()
                .withProperty("spring.grpc.client.channel.billing.target", PayServiceTransportPolicy.TEST_TARGET)
                .withProperty("spring.grpc.client.channel.billing.ssl.enabled", "true")
                .withProperty("app.external.payservice-plaintext-enabled", "false")
                .withProperty("app.grpc.billing-trust-cert-collection",
                        PayServiceTransportPolicy.STAGING_TRUST);
        Map<String, String> testRaw = canonicalEnvironmentWithTransport();
        testRaw.put("ENV", "test");
        testRaw.put("BILLING_GRPC_ADDR", PayServiceTransportPolicy.TEST_TARGET);
        testRaw.put("GRPC_CLIENT_BILLING_SECURITY_TRUST_CERT_COLLECTION",
                PayServiceTransportPolicy.STAGING_TRUST);
        assertDoesNotThrow(() -> PayServiceJwtStartupGuard.validatePayTransport(testEnvironment, testRaw));

        MockEnvironment productionEnvironment = new MockEnvironment()
                .withProperty("spring.grpc.client.channel.billing.target", PayServiceTransportPolicy.PRODUCTION_TARGET)
                .withProperty("spring.grpc.client.channel.billing.ssl.enabled", "true")
                .withProperty("app.external.payservice-plaintext-enabled", "false");
        Map<String, String> productionRaw = new HashMap<>(testRaw);
        productionRaw.put("ENV", "production");
        productionRaw.put("BILLING_GRPC_ADDR", PayServiceTransportPolicy.PRODUCTION_TARGET);
        assertThrows(IllegalStateException.class,
                () -> PayServiceJwtStartupGuard.validatePayTransport(
                        productionEnvironment, productionRaw));
    }

    @Test
    void rejectsProfileSecretMismatchLegacyAndExpandedScope() {
        AppProperties properties = validProperties();
        assertThrows(IllegalStateException.class,
                () -> PayServiceJwtStartupGuard.validateFinalConfiguration(
                        properties, canonicalEnvironment(), new String[]{"test"}, new String[]{"default"}, false));

        Map<String, String> mismatched = canonicalEnvironment();
        mismatched.put("APP_EXTERNAL_PAYSERVICE_JWT_SECRET", "different-pay-secret-value-with-32-bytes");
        assertThrows(IllegalStateException.class,
                () -> PayServiceJwtStartupGuard.validateFinalConfiguration(
                        properties, mismatched, new String[0], new String[]{"default"}, false));

        Map<String, String> legacy = canonicalEnvironment();
        legacy.put("APP_EXTERNAL_PAYSERVICE_JWT", "legacy-private-token");
        assertThrows(IllegalStateException.class,
                () -> PayServiceJwtStartupGuard.validateFinalConfiguration(
                        properties, legacy, new String[0], new String[]{"default"}, false));

        AppProperties expanded = validProperties();
        expanded.getExternal().getPayserviceJwt().setScopes(
                PayServiceJwtConfigurationValidator.REQUIRED_SCOPES_VALUE + ",billing.admin");
        assertThrows(IllegalArgumentException.class,
                () -> PayServiceJwtStartupGuard.validateFinalConfiguration(
                        expanded, canonicalEnvironment(), new String[0], new String[]{"default"}, false));

    }

    @Test
    void testClasspathMarkerIsTheOnlyTestBypass() {
        assertDoesNotThrow(() -> PayServiceJwtStartupGuard.validateFinalConfiguration(
                new AppProperties(), Map.of(), new String[]{"test"}, new String[]{"default"}, true));
    }

    static AppProperties validProperties() {
        AppProperties properties = new AppProperties();
        properties.getExternal().getUserserviceJwt().setSecret(USER_SECRET);
        properties.getExternal().setAiserviceHmacCaller("aisocialgame");
        properties.getExternal().setAiserviceHmacSecret("aisocialgame-ai-hmac-guard-secret-32-bytes");
        properties.getExternal().getPayserviceJwt().setSecret(PAY_SECRET);
        return properties;
    }

    static Map<String, String> canonicalEnvironment() {
        Map<String, String> values = new HashMap<>();
        values.put("APP_EXTERNAL_GRPC_AUTH_REQUIRED", "true");
        values.put("APP_EXTERNAL_PAYSERVICE_JWT_CALLER_ID", "aisocialgame");
        values.put("APP_EXTERNAL_PAYSERVICE_JWT_ISSUER", "aisocialgame");
        values.put("APP_EXTERNAL_PAYSERVICE_JWT_SERVICE", "aisocialgame");
        values.put("APP_EXTERNAL_PAYSERVICE_JWT_SECRET", PAY_SECRET);
        values.put("APP_EXTERNAL_PAYSERVICE_JWT_AUDIENCE", "aienie-payservice-grpc");
        values.put("APP_EXTERNAL_PAYSERVICE_JWT_ROLE", "SERVICE");
        values.put("APP_EXTERNAL_PAYSERVICE_JWT_TTL_SECONDS", "300");
        values.put("APP_EXTERNAL_PAYSERVICE_JWT_SCOPES",
                PayServiceJwtConfigurationValidator.REQUIRED_SCOPES_VALUE);
        return values;
    }

    private static Map<String, String> canonicalEnvironmentWithTransport() {
        Map<String, String> values = canonicalEnvironment();
        values.put("BILLING_GRPC_ADDR", "static://localpayservice.testhut.top:22021");
        values.put("BILLING_GRPC_NEGOTIATION_TYPE", "TLS");
        values.put("BILLING_GRPC_PLAINTEXT_ENABLED", "false");
        values.put("GRPC_CLIENT_BILLING_SECURITY_TRUST_CERT_COLLECTION", LOCAL_TRUST);
        values.put("ENV", "local");
        return values;
    }
}
