package com.aisocialgame.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.HashMap;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AiServiceTransportStartupGuardTest {
    private static final String LOCAL_TRUST = localTrust();

    private static String localTrust() {
        Path root = Path.of("").toAbsolutePath();
        Path file = root.resolve("scripts/windows/local-trust/localcert-root-ca.crt");
        if (!Files.isRegularFile(file)) {
            file = root.resolve("../scripts/windows/local-trust/localcert-root-ca.crt");
        }
        return file.normalize().toUri().toString();
    }
    @Test
    void acceptsCanonicalLocalAndTestTargetsWithTls() {
        MockEnvironment local = environment(
                AiServiceTransportPolicy.LOCAL_TARGET,
                "TLS",
                LOCAL_TRUST);
        assertDoesNotThrow(() -> AiServiceTransportStartupGuard.validateBeforeServerCreation(
                local, raw("local", AiServiceTransportPolicy.LOCAL_TARGET,
                        LOCAL_TRUST)));

        MockEnvironment test = environment(
                AiServiceTransportPolicy.TEST_TARGET, "TLS", AiServiceTransportPolicy.STAGING_TRUST);
        assertDoesNotThrow(() -> AiServiceTransportStartupGuard.validateBeforeServerCreation(
                test, raw("test", AiServiceTransportPolicy.TEST_TARGET,
                        AiServiceTransportPolicy.STAGING_TRUST)));
    }

    @Test
    void rejectsOldLocalPortAndFinalOrRawOverrides() {
        Map<String, String> raw = raw("local", AiServiceTransportPolicy.LOCAL_TARGET,
                LOCAL_TRUST);
        assertThrows(IllegalStateException.class,
                () -> AiServiceTransportStartupGuard.validateBeforeServerCreation(
                        environment("static://localaiservice.testhut.top:443", "TLS",
                                LOCAL_TRUST), raw));

        Map<String, String> oldPort = new HashMap<>(raw);
        oldPort.put("AI_GRPC_ADDR", "static://localaiservice.testhut.top:443");
        assertThrows(IllegalStateException.class,
                () -> AiServiceTransportStartupGuard.validateBeforeServerCreation(
                        environment("static://localaiservice.testhut.top:443", "TLS",
                                LOCAL_TRUST), oldPort));

        assertThrows(IllegalStateException.class,
                () -> AiServiceTransportStartupGuard.validateBeforeServerCreation(
                        environment(AiServiceTransportPolicy.LOCAL_TARGET, "PLAINTEXT",
                                LOCAL_TRUST), raw));
    }

    @Test
    void rejectsMergedGlobalAndAiSpecificTlsOverrides() {
        Map<String, String> localRaw = raw("local", AiServiceTransportPolicy.LOCAL_TARGET,
                LOCAL_TRUST);
        MockEnvironment globalAuthority = environment(
                AiServiceTransportPolicy.LOCAL_TARGET,
                "TLS",
                LOCAL_TRUST)
                .withProperty("spring.grpc.client.channel.ai.override-authority", "attacker.invalid");
        assertThrows(IllegalStateException.class,
                () -> AiServiceTransportStartupGuard.validateBeforeServerCreation(globalAuthority, localRaw));

        MockEnvironment clientKey = environment(
                AiServiceTransportPolicy.LOCAL_TARGET,
                "TLS",
                LOCAL_TRUST)
                .withProperty("spring.grpc.client.channel.ai.ssl.bundle", "attacker-bundle");
        assertThrows(IllegalStateException.class,
                () -> AiServiceTransportStartupGuard.validateBeforeServerCreation(clientKey, localRaw));

        Map<String, String> testRaw = raw(
                "test", AiServiceTransportPolicy.TEST_TARGET, AiServiceTransportPolicy.STAGING_TRUST);
        MockEnvironment globalTrust = environment(AiServiceTransportPolicy.TEST_TARGET, "TLS", null)
                .withProperty("spring.grpc.client.channel.ai.bypass-certificate-validation", "true");
        assertThrows(IllegalStateException.class,
                () -> AiServiceTransportStartupGuard.validateBeforeServerCreation(globalTrust, testRaw));
    }

    @Test
    void rejectsWrongTrustAndProductionWithoutSignedPreactivationAuthority() {
        Map<String, String> wrongTrust = raw("local", AiServiceTransportPolicy.LOCAL_TARGET,
                "file:/private/attacker-ca.crt");
        assertThrows(IllegalStateException.class,
                () -> AiServiceTransportStartupGuard.validateBeforeServerCreation(
                        environment(AiServiceTransportPolicy.LOCAL_TARGET, "TLS",
                                "file:/private/attacker-ca.crt"), wrongTrust));

        assertThrows(IllegalStateException.class,
                () -> AiServiceTransportStartupGuard.validateBeforeServerCreation(
                        environment(AiServiceTransportPolicy.PRODUCTION_TARGET, "TLS", null),
                        raw("production", AiServiceTransportPolicy.PRODUCTION_TARGET, "")));
    }

    private static MockEnvironment environment(String address, String negotiationType, String trust) {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.grpc.client.channel.ai.target", address)
                .withProperty("spring.grpc.client.channel.ai.ssl.enabled",
                        Boolean.toString("TLS".equals(negotiationType)));
        if (trust != null) {
            environment.withProperty("app.grpc.ai-trust-cert-collection", trust);
        }
        return environment;
    }

    private static Map<String, String> raw(String runtimeEnvironment, String address, String trust) {
        return Map.of(
                "ENV", runtimeEnvironment,
                "AI_GRPC_ADDR", address,
                "AI_GRPC_NEGOTIATION_TYPE", "TLS",
                "GRPC_CLIENT_AI_SECURITY_TRUST_CERT_COLLECTION", trust);
    }
}
