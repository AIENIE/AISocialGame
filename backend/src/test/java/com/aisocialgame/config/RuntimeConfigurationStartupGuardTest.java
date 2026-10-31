package com.aisocialgame.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RuntimeConfigurationStartupGuardTest {

    @Test
    void acceptsCanonicalStagingProjectAndListener() {
        MockEnvironment environment = canonicalEnvironment()
                .withProperty("server.address", "0.0.0.0")
                .withProperty("server.port", "20030");
        assertDoesNotThrow(() -> RuntimeConfigurationStartupGuard.validateBeforeServerCreation(
                environment,
                Map.of("APP_PROJECT_KEY", AppProperties.CANONICAL_PROJECT_KEY,
                        "ENV", "test")));
    }

    @Test
    void rejectsRawOrFinalProjectKeyMismatch() {
        assertThrows(IllegalStateException.class,
                () -> RuntimeConfigurationStartupGuard.validateBeforeServerCreation(
                        canonicalEnvironment(), Map.of("APP_PROJECT_KEY", "attacker")));

        MockEnvironment finalOverride = canonicalEnvironment();
        finalOverride.setProperty("app.project-key", "attacker");
        assertThrows(IllegalStateException.class,
                () -> RuntimeConfigurationStartupGuard.validateBeforeServerCreation(
                        finalOverride, Map.of("APP_PROJECT_KEY", AppProperties.CANONICAL_PROJECT_KEY)));
    }

    @Test
    void rejectsFinalListenerMismatch() {
        MockEnvironment wrongAddress = canonicalEnvironment()
                .withProperty("server.address", "127.0.0.1")
                .withProperty("server.port", "20030");
        assertThrows(IllegalStateException.class,
                () -> RuntimeConfigurationStartupGuard.validateBeforeServerCreation(
                        wrongAddress,
                        Map.of("APP_PROJECT_KEY", AppProperties.CANONICAL_PROJECT_KEY,
                                "ENV", "test")));

        MockEnvironment wrongPort = canonicalEnvironment()
                .withProperty("server.address", "0.0.0.0")
                .withProperty("server.port", "11031");
        assertThrows(IllegalStateException.class,
                () -> RuntimeConfigurationStartupGuard.validateBeforeServerCreation(
                        wrongPort,
                        Map.of("APP_PROJECT_KEY", AppProperties.CANONICAL_PROJECT_KEY,
                                "ENV", "test")));
    }

    @Test
    void ordinaryLocalAndProductionCanonicalListenersRemainValid() {
        for(String env:java.util.List.of("local","production")) assertDoesNotThrow(() -> RuntimeConfigurationStartupGuard.validateBeforeServerCreation(canonicalEnvironment().withProperty("server.address","127.0.0.1").withProperty("server.port","11031"),Map.of("APP_PROJECT_KEY",AppProperties.CANONICAL_PROJECT_KEY,"ENV",env)));
    }

    private static MockEnvironment canonicalEnvironment() {
        return new MockEnvironment()
                .withProperty("app.project-key", AppProperties.CANONICAL_PROJECT_KEY);
    }
}
