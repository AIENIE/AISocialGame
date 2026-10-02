package com.aienie.configpair;

import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeConfigurationTest {
    private Properties aliases() {
        Properties aliases = new Properties();
        aliases.setProperty("ENV", "admin.authentication.environment");
        aliases.setProperty("DB_HOST", "database.host");
        aliases.setProperty("ADMIN_PASSWORD", "admin.password");
        return aliases;
    }

    @Test void yamlValuesReplaceLegacyNonsecretVariablesAndKeepLiteralSecrets() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("admin.authentication.environment", "production")
                .withProperty("database.host", "database.example.test")
                .withProperty("admin.password", " $pa#ss='x'= ");
        Map<String, String> result = RuntimeConfiguration.resolve(environment,
                Map.of("ENV", "local", "DB_HOST", "old.example.test"), aliases());
        assertEquals("production", result.get("ENV"));
        assertEquals("database.example.test", result.get("DB_HOST"));
        assertEquals(" $pa#ss='x'= ", result.get("ADMIN_PASSWORD"));
        assertThrows(UnsupportedOperationException.class, () -> result.put("ENV", "local"));
    }

    @Test void selectorsAreNotTrimmedOrNormalized() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("admin.authentication.environment", " production ");
        assertEquals(" production ", RuntimeConfiguration.resolve(environment, Map.of(), aliases()).get("ENV"));
    }

    @Test void missingSecretDoesNotAcquireDefaultCredentials() {
        MockEnvironment environment = new MockEnvironment().withProperty("admin.password", "${MISSING_PASSWORD}");
        assertFalse(RuntimeConfiguration.resolve(environment, Map.of(), aliases()).containsKey("ADMIN_PASSWORD"));
    }

    @Test void administratorIdentityAndPasswordAlternativesAreCheckedBeforeBeans() {
        Properties aliases = new Properties();
        aliases.setProperty("ADMIN_USERNAME", "admin.username");
        assertThrows(IllegalStateException.class, () -> RuntimeConfiguration.validateAdministratorCredentials(
                Map.of("ADMIN_USERNAME", "operator"), aliases));
        assertThrows(IllegalStateException.class, () -> RuntimeConfiguration.validateAdministratorCredentials(
                Map.of("ADMIN_PASSWORD_HASH", "test-only-hash"), aliases));
        assertDoesNotThrow(() -> RuntimeConfiguration.validateAdministratorCredentials(
                Map.of("ADMIN_USERNAME", "operator", "ADMIN_PASSWORD", " $literal#='x'= "), aliases));
    }

    @Test void databaseAndTotpCredentialsFailBeforeBeans() {
        assertThrows(IllegalStateException.class, () -> RuntimeConfiguration.validateDatabaseUrl(""));
        assertThrows(IllegalStateException.class, () -> RuntimeConfiguration.validateDatabaseUrl("jdbc:mysql://:3306/database"));
        assertDoesNotThrow(() -> RuntimeConfiguration.validateDatabaseUrl("jdbc:mysql://database.example.test:3306/database?useSSL=true"));
        Properties aliases = new Properties();
        aliases.setProperty("MYSQL_PASSWORD", "spring.datasource.password");
        aliases.setProperty("ADMIN_TOTP_ENCRYPTION_KEYS", "admin.encryption-keys");
        assertThrows(IllegalStateException.class, () -> RuntimeConfiguration.validateRequiredCredentials(
                Map.of("AUTH_MODE", "password"), aliases));
        assertThrows(IllegalStateException.class, () -> RuntimeConfiguration.validateRequiredCredentials(
                Map.of("AUTH_MODE", "totp", "MYSQL_PASSWORD", "literal$#=password"), aliases));
        assertDoesNotThrow(() -> RuntimeConfiguration.validateRequiredCredentials(
                Map.of("AUTH_MODE", "totp", "MYSQL_PASSWORD", "literal$#=password",
                       "ADMIN_TOTP_ENCRYPTION_KEYS", "v1:synthetic-key"), aliases));
        Properties versioned = new Properties();
        versioned.setProperty("ADMIN_TOTP_KEY_V1", "admin.totp.keys.v1");
        versioned.setProperty("ADMIN_TOTP_SECRET", "runtime.configuration.ADMIN_TOTP_SECRET");
        assertDoesNotThrow(() -> RuntimeConfiguration.validateRequiredCredentials(
                Map.of("AUTH_MODE", "totp", "ADMIN_TOTP_KEY_V1", "synthetic-key"), versioned));
        assertThrows(IllegalStateException.class, () -> RuntimeConfiguration.validateRequiredCredentials(
                Map.of("AUTH_MODE", "totp", "ADMIN_TOTP_ACTIVE_KEY_VERSION", "v2",
                       "ADMIN_TOTP_KEY_V1", "synthetic-key"), versioned));
    }

    @Test void invalidSelectorsFailBeforeAnyContextOrDriverIsCreated() {
        MockEnvironment environment = new MockEnvironment();
        for (String value : new String[] {"", "TEST", " test ", "staging"}) {
            assertThrows(IllegalStateException.class, () -> RuntimeConfiguration.validateBeforeDependencies(
                    environment, Map.of("ENV", value, "AUTH_MODE", "totp")));
        }
        assertThrows(IllegalStateException.class, () -> RuntimeConfiguration.validateBeforeDependencies(
                environment, Map.of("ENV", "production", "AUTH_MODE", "password")));
        assertDoesNotThrow(() -> RuntimeConfiguration.validateBeforeDependencies(
                environment, Map.of("ENV", "test", "AUTH_MODE", "totp")));
    }

    @Test void winningConfigurationRequiresSecretsAndNativeOverridesKeepTheirPrecedence() {
        MockEnvironment environment = new MockEnvironment();
        environment.getPropertySources().addLast(new org.springframework.core.env.MapPropertySource(
                "Config resource external", Map.of("database.password", "${REQUIRED_PASSWORD}")));
        assertThrows(IllegalStateException.class, () -> RuntimeConfiguration.validateBeforeDependencies(
                environment, Map.of("ENV", "local", "AUTH_MODE", "password")));
        environment.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
                "commandLineArgs", Map.of("database.password", " $native#='x'= ")));
        assertDoesNotThrow(() -> RuntimeConfiguration.validateBeforeDependencies(
                environment, Map.of("ENV", "local", "AUTH_MODE", "password")));
    }
}
