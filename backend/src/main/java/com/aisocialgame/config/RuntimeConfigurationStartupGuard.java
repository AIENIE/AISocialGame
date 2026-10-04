package com.aisocialgame.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Locks the final project identity and HTTP listener before listener creation. */
@Component
public final class RuntimeConfigurationStartupGuard {
    public static final String ACCEPTANCE_SELECTOR = "runtime.acceptance.admin-emergency-codes";
    private static final String ACCEPTANCE_ENV = "AIENIE_ADMIN_EMERGENCY_ACCEPTANCE";
    private static final String ACCEPTANCE_DATABASE = "/aienie_emergency_20261004_social";
    private final ConfigurableEnvironment environment;
    private final ObjectProvider<RuntimeProfileTestAuthorization> testAuthorization;

    public RuntimeConfigurationStartupGuard(
            ConfigurableEnvironment environment,
            ObjectProvider<RuntimeProfileTestAuthorization> testAuthorization) {
        this.environment = environment;
        this.testAuthorization = testAuthorization;
    }

    @PostConstruct
    public void validate() {
        if (testAuthorization.getIfAvailable() == null) {
            validateBeforeServerCreation(environment, com.aienie.configpair.RuntimeConfiguration.getenv());
        }
    }

    public static void validateBeforeServerCreation(
            ConfigurableEnvironment environment,
            Map<String, String> rawEnvironment) {
        ProjectIdentityPolicy.validateRaw(rawEnvironment);
        requireExact("app.project-key", ProjectIdentityPolicy.PROJECT_KEY,
                environment.getProperty("app.project-key"));
        String runtime = rawEnvironment == null ? "" : rawEnvironment.get("ENV");
        String selector = environment.getProperty(ACCEPTANCE_SELECTOR, "false");
        String selected = rawEnvironment.getOrDefault(ACCEPTANCE_ENV, "false");
        if (!"false".equals(selector) || !"false".equals(selected)) {
            validateAcceptance(environment, rawEnvironment, selector, selected);
            return;
        }
        String expectedAddress = "test".equals(runtime) ? "0.0.0.0" : "127.0.0.1";
        String expectedPort = "test".equals(runtime) ? "20030" : "11031";
        requireExact("server.address", expectedAddress, environment.getProperty("server.address"));
        requireExact("server.port", expectedPort, environment.getProperty("server.port"));
    }

    private static void validateAcceptance(ConfigurableEnvironment environment, Map<String, String> raw,
                                           String selector, String selected) {
        requireExact(ACCEPTANCE_SELECTOR, "true", selector);
        requireExact(ACCEPTANCE_ENV, "true", selected);
        // A normal process variable/command-line property cannot opt in to another listener.
        boolean yamlSelector = false;
        for (var source : environment.getPropertySources()) {
            // Spring Boot's attached view delegates to the underlying sources; inspect their actual origin.
            if (source.getName().equals("configurationProperties")) continue;
            Object value = source.getProperty(ACCEPTANCE_SELECTOR);
            if (value == null) continue;
            yamlSelector = source.getName().startsWith("Config resource") && "true".equals(value.toString());
            break;
        }
        if (!yamlSelector) throw new IllegalStateException("Emergency acceptance requires an explicit YAML selector");
        requireExact("ENV", "local", raw.get("ENV"));
        requireExact("AUTH_MODE", "totp", raw.get("AUTH_MODE"));
        requireExact("AIENIE_RUNTIME_PLANE", "windows-local", raw.get("AIENIE_RUNTIME_PLANE"));
        if (!System.getProperty("os.name", "").startsWith("Windows")
                || !java.util.Arrays.equals(environment.getActiveProfiles(), new String[]{"local"}))
            throw new IllegalStateException("Emergency acceptance requires native Windows and only the local profile");
        requireExact("server.address", "127.0.0.1", environment.getProperty("server.address"));
        requireExact("server.port", "12031", environment.getProperty("server.port"));
        validateAcceptanceDatabase(environment.getProperty("spring.datasource.url"));
        AppProperties properties = new AppProperties();
        properties.getAdmin().setTotpEncryptionKeys(environment.getProperty("app.admin.totp-encryption-keys", ""));
        properties.getAdmin().setTotpActiveKeyVersion(environment.getProperty("app.admin.totp-active-key-version", ""));
        if (!new com.aisocialgame.adminauth.AdminAuthCrypto(properties).hasUsableActiveKey())
            throw new IllegalStateException("Emergency acceptance requires a usable administrator TOTP keyring");
    }

    private static void validateAcceptanceDatabase(String url) {
        try {
            if (url == null || !url.startsWith("jdbc:mysql://")) throw new IllegalArgumentException();
            java.net.URI uri = java.net.URI.create(url.substring(5));
            if (!"localmysql.testhut.top".equals(uri.getHost()) || uri.getPort() != 23306
                    || !ACCEPTANCE_DATABASE.equals(uri.getRawPath()) || uri.getRawUserInfo() != null || uri.getFragment() != null)
                throw new IllegalArgumentException();
            Map<String, String> allowed = Map.of("useSSL", "false", "allowPublicKeyRetrieval", "true",
                    "serverTimezone", "UTC", "connectTimeout", "10000", "socketTimeout", "30000");
            var seen = new java.util.HashSet<String>();
            if (uri.getRawQuery() != null) for (String option : uri.getRawQuery().split("&", -1)) {
                String[] parts = option.split("=", -1);
                if (parts.length != 2 || !seen.add(parts[0]) || !parts[1].equals(allowed.get(parts[0])))
                    throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException error) {
            throw new IllegalStateException("Emergency acceptance requires its isolated schema on the canonical shared MySQL target");
        }
    }

    private static void requireExact(String name, String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new IllegalStateException("Final runtime configuration is not canonical: " + name);
        }
    }
}
