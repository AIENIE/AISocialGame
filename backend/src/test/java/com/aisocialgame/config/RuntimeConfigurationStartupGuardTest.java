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
    void acceptsOnlyExplicitYamlLocalWindowsTotpIsolatedAcceptance() {
        assertDoesNotThrow(() -> RuntimeConfigurationStartupGuard.validateBeforeServerCreation(acceptanceEnvironment(), acceptanceRaw()));
    }

    @Test
    void attachedSpringConfigurationViewPreservesYamlOriginAndRejectsOverrides() {
        var environment = acceptanceEnvironment();
        org.springframework.boot.context.properties.source.ConfigurationPropertySources.attach(environment);
        assertDoesNotThrow(() -> RuntimeConfigurationStartupGuard.validateBeforeServerCreation(environment, acceptanceRaw()));
        environment.getPropertySources().addAfter("configurationProperties", new org.springframework.core.env.MapPropertySource(
                "commandLineArgs", Map.of(RuntimeConfigurationStartupGuard.ACCEPTANCE_SELECTOR, true)));
        assertThrows(IllegalStateException.class, () -> RuntimeConfigurationStartupGuard.validateBeforeServerCreation(environment, acceptanceRaw()));
    }

    @Test
    void realConfigDataYamlPassesBeforeAnyDependenciesAreCreated(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        var file = directory.resolve("acceptance.yml");
        java.nio.file.Files.writeString(file, """
                spring:
                  profiles:
                    active: local
                  datasource:
                    url: jdbc:mysql://localmysql.testhut.top:23306/aienie_emergency_20261004_social?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&connectTimeout=10000&socketTimeout=30000
                server:
                  address: 127.0.0.1
                  port: 12031
                runtime:
                  acceptance:
                    admin-emergency-codes: true
                app:
                  project-key: aisocialgame
                  admin:
                    totp-encryption-keys: v1:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
                    totp-active-key-version: v1
                """);
        var environment = new org.springframework.core.env.StandardEnvironment();
        environment.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
                "testConfigLocation", Map.of("spring.config.location", file.toUri().toString())));
        org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor.applyTo(environment);
        org.springframework.boot.context.properties.source.ConfigurationPropertySources.attach(environment);
        assertDoesNotThrow(() -> RuntimeConfigurationStartupGuard.validateBeforeServerCreation(environment, acceptanceRaw()));
    }

    @Test
    void ordinaryPortOrProcessSelectorOverrideCannotEnableAcceptance() {
        var ordinary=canonicalEnvironment().withProperty("server.address","127.0.0.1").withProperty("server.port","12031");
        assertThrows(IllegalStateException.class, () -> RuntimeConfigurationStartupGuard.validateBeforeServerCreation(ordinary,Map.of("APP_PROJECT_KEY",AppProperties.CANONICAL_PROJECT_KEY,"ENV","local")));
        var processOverride=acceptanceEnvironment();
        processOverride.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("systemEnvironmentOverride",Map.of(RuntimeConfigurationStartupGuard.ACCEPTANCE_SELECTOR,true)));
        assertThrows(IllegalStateException.class, () -> RuntimeConfigurationStartupGuard.validateBeforeServerCreation(processOverride,acceptanceRaw()));
    }

    @Test
    void rejectsAcceptanceInAnotherEnvironmentModeOrRuntimePlane() {
        for(String key:java.util.List.of("ENV","AUTH_MODE","AIENIE_RUNTIME_PLANE","AIENIE_ADMIN_EMERGENCY_ACCEPTANCE")) {
            var raw=new java.util.HashMap<>(acceptanceRaw());
            raw.put(key,switch(key) { case "ENV" -> "test"; case "AUTH_MODE" -> "password"; case "AIENIE_RUNTIME_PLANE" -> "linux"; default -> "false"; });
            assertThrows(IllegalStateException.class, () -> RuntimeConfigurationStartupGuard.validateBeforeServerCreation(acceptanceEnvironment(),raw));
        }
        var production=new java.util.HashMap<>(acceptanceRaw());production.put("ENV","production");
        assertThrows(IllegalStateException.class, () -> RuntimeConfigurationStartupGuard.validateBeforeServerCreation(acceptanceEnvironment(),production));
    }

    @Test
    void rejectsAcceptanceWithAnotherProfileOrNonWindowsPlatform() {
        for(String[] profiles:new String[][] { {"test"},{"local","test"},{} }) {
            var environment=acceptanceEnvironment();environment.setActiveProfiles(profiles);
            assertThrows(IllegalStateException.class, () -> RuntimeConfigurationStartupGuard.validateBeforeServerCreation(environment,acceptanceRaw()));
        }
        String previous=System.getProperty("os.name");
        try { System.setProperty("os.name","Linux"); assertThrows(IllegalStateException.class, () -> RuntimeConfigurationStartupGuard.validateBeforeServerCreation(acceptanceEnvironment(),acceptanceRaw())); }
        finally { System.setProperty("os.name",previous); }
    }

    @Test
    void rejectsAcceptanceWhenListenerDatabaseOrTotpKeyringDiffers() {
        Map<String,String> badProperties=Map.of("server.address","0.0.0.0","server.port","12032", "spring.datasource.url","jdbc:mysql://localmysql.testhut.top:23306/aisocialgame", "app.admin.totp-encryption-keys","", "app.admin.totp-active-key-version","missing");
        for(var entry:badProperties.entrySet()) {
            var environment=acceptanceEnvironment().withProperty(entry.getKey(),entry.getValue());
            assertThrows(IllegalStateException.class, () -> RuntimeConfigurationStartupGuard.validateBeforeServerCreation(environment,acceptanceRaw()));
        }
        for(String url:java.util.List.of("jdbc:mysql://other.testhut.top:23306/aienie_emergency_20261004_social", "jdbc:mysql://localmysql.testhut.top:3306/aienie_emergency_20261004_social", "jdbc:mysql://localmysql.testhut.top:23306/aienie_emergency_20261004_pdf", "jdbc:mysql://user:password@localmysql.testhut.top:23306/aienie_emergency_20261004_social", "jdbc:mysql://localmysql.testhut.top:23306/aienie_emergency_20261004_social?useSSL=false&useSSL=false", "jdbc:mysql://localmysql.testhut.top:23306/aienie_emergency_20261004_social?allowLoadLocalInfile=true")) {
            var environment=acceptanceEnvironment().withProperty("spring.datasource.url",url);
            assertThrows(IllegalStateException.class, () -> RuntimeConfigurationStartupGuard.validateBeforeServerCreation(environment,acceptanceRaw()));
        }
    }

    @Test
    void ordinaryLocalAndProductionCanonicalListenersRemainValid() {
        for(String env:java.util.List.of("local","production")) assertDoesNotThrow(() -> RuntimeConfigurationStartupGuard.validateBeforeServerCreation(canonicalEnvironment().withProperty("server.address","127.0.0.1").withProperty("server.port","11031"),Map.of("APP_PROJECT_KEY",AppProperties.CANONICAL_PROJECT_KEY,"ENV",env)));
    }

    private static Map<String,String> acceptanceRaw() {
        return Map.of("APP_PROJECT_KEY",AppProperties.CANONICAL_PROJECT_KEY,"ENV","local","AUTH_MODE","totp","AIENIE_RUNTIME_PLANE","windows-local","AIENIE_ADMIN_EMERGENCY_ACCEPTANCE","true");
    }
    private static MockEnvironment acceptanceEnvironment() {
        var environment=canonicalEnvironment().withProperty("server.address","127.0.0.1").withProperty("server.port","12031")
                .withProperty("spring.datasource.url","jdbc:mysql://localmysql.testhut.top:23306/aienie_emergency_20261004_social?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&connectTimeout=10000&socketTimeout=30000")
                .withProperty("app.admin.totp-encryption-keys","v1:"+java.util.Base64.getEncoder().encodeToString(new byte[32]))
                .withProperty("app.admin.totp-active-key-version","v1");
        environment.setActiveProfiles("local");
        environment.getPropertySources().addLast(new org.springframework.core.env.MapPropertySource("Config resource 'acceptance-test.yml'",Map.of(RuntimeConfigurationStartupGuard.ACCEPTANCE_SELECTOR,true)));
        return environment;
    }

    private static MockEnvironment canonicalEnvironment() {
        return new MockEnvironment()
                .withProperty("app.project-key", AppProperties.CANONICAL_PROJECT_KEY);
    }
}
