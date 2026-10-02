package com.aienie.configpair;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.SimpleCommandLinePropertySource;
import org.springframework.core.env.StandardEnvironment;

/** A single effective ConfigData view for preflight and legacy configuration consumers. */
public final class RuntimeConfiguration {
    private static volatile ConfigurableEnvironment configured;
    private static volatile Map<String, String> values;

    private RuntimeConfiguration() { }

    public static synchronized void initialize(String[] args) {
        StandardEnvironment environment = new StandardEnvironment();
        String applicationFile = System.getenv("AIENIE_APPLICATION_FILE");
        if ((applicationFile == null || applicationFile.isBlank())
                && environment.getProperty("spring.config.additional-location") == null
                && java.nio.file.Files.isRegularFile(java.nio.file.Path.of("/app/application.yml")))
            applicationFile = "file:/app/application.yml";
        if (applicationFile != null && !applicationFile.isBlank()) {
            environment.getPropertySources().addFirst(new MapPropertySource("configPairApplicationFile",
                    Map.of("spring.config.additional-location", applicationFile)));
        }
        if (args.length > 0) {
            environment.getPropertySources().addFirst(new SimpleCommandLinePropertySource(args));
        }
        // This prepares YAML/profile/import precedence without creating beans,
        // opening drivers, or constructing the application context.
        ConfigDataEnvironmentPostProcessor.applyTo(environment);
        Properties aliases = new Properties();
        try (InputStream stream = RuntimeConfiguration.class.getResourceAsStream("/config-pair-aliases.properties")) {
            if (stream == null) {
                throw new IllegalStateException("runtime configuration mapping is unavailable");
            }
            aliases.load(stream);
        } catch (IOException exception) {
            throw new IllegalStateException("cannot load runtime configuration mapping");
        }
        values = resolve(environment, System.getenv(), aliases);
        validateBeforeDependencies(environment, values);
        validateAdministratorCredentials(values, aliases);
        validateRequiredCredentials(values, aliases);
        validateDatabaseUrl(environment.getProperty("spring.datasource.url"));
        environment.getPropertySources().addLast(new MapPropertySource(
                "configPairAliases", new LinkedHashMap<String, Object>(values)));
        configured = environment;
    }

    /** Resolve a Spring-managed context without sharing mutable test/application state. */
    public static Map<String, String> fromEnvironment(Environment environment) {
        Properties aliases = new Properties();
        try (InputStream stream = RuntimeConfiguration.class.getResourceAsStream("/config-pair-aliases.properties")) {
            if (stream == null) throw new IllegalStateException("runtime configuration mapping is unavailable");
            aliases.load(stream);
        } catch (IOException exception) {
            throw new IllegalStateException("cannot load runtime configuration mapping");
        }
        Map<String, String> original = new LinkedHashMap<>(System.getenv());
        for (String key : aliases.stringPropertyNames()) {
            String value = environment.getProperty(key);
            if (value != null) original.put(key, value);
        }
        Map<String, String> result = new LinkedHashMap<>(resolve(environment, original, aliases));
        if (environment instanceof ConfigurableEnvironment configurable) {
            var explicitKeys = new java.util.HashSet<String>();
            for (var source : configurable.getPropertySources()) {
                if (source.getName().equals(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)
                        || source.getName().startsWith("Config resource") || source.getName().equals("configPairAliases")) continue;
                var keys = new java.util.HashSet<String>(aliases.stringPropertyNames());
                if (source instanceof EnumerablePropertySource<?> enumerable) {
                    for (String property : enumerable.getPropertyNames()) {
                        if (property.matches("[A-Z][A-Z0-9_]*")) keys.add(property);
                    }
                }
                for (String key : keys) {
                    Object explicit = source.getProperty(key);
                    if (explicit != null && explicitKeys.add(key)) result.put(key, explicit.toString());
                }
            }
        }
        return Collections.unmodifiableMap(result);
    }

    static Map<String, String> resolve(Environment environment, Map<String, String> original, Properties aliases) {
        Map<String, String> result = new LinkedHashMap<>(original);
        if (environment instanceof ConfigurableEnvironment configurable) {
            for (var source : configurable.getPropertySources()) {
                if (source instanceof EnumerablePropertySource<?> enumerable) {
                    for (String property : enumerable.getPropertyNames()) {
                        if (property.startsWith("runtime.configuration.")) {
                            String key = property.substring("runtime.configuration.".length());
                            if (key.matches("[A-Z][A-Z0-9_]*")) {
                                try { result.put(key, environment.getProperty(property)); }
                                catch (IllegalArgumentException missingSecret) { result.remove(key); }
                            }
                        }
                    }
                }
            }
        }
        for (String key : aliases.stringPropertyNames()) {
            for (String property : aliases.getProperty(key).split(",")) {
                try {
                    String value = environment.getProperty(property);
                    if (value != null) {
                        result.put(key, value);
                        break;
                    }
                } catch (IllegalArgumentException unresolvedSecret) {
                    // Missing optional secrets stay absent. Required secrets
                    // are rejected by the existing preflight/binding validators.
                    result.remove(key);
                }
            }
        }
        return Collections.unmodifiableMap(result);
    }

    public static String getenv(String name) {
        return getenv().get(name);
    }

    static void validateAdministratorCredentials(Map<String, String> effective, Properties aliases) {
        String user = aliases.containsKey("APP_ADMIN_LOGIN_USERNAME") ? "APP_ADMIN_LOGIN_USERNAME"
                : aliases.containsKey("APP_ADMIN_USERNAME") ? "APP_ADMIN_USERNAME" : "ADMIN_USERNAME";
        String hash = user.equals("APP_ADMIN_LOGIN_USERNAME") ? "APP_ADMIN_LOGIN_PASSWORD_HASH"
                : user.equals("APP_ADMIN_USERNAME") ? "APP_ADMIN_PASSWORD_HASH" : "ADMIN_PASSWORD_HASH";
        String password = user.equals("ADMIN_USERNAME") ? "ADMIN_PASSWORD" : "";
        if (!aliases.containsKey(user)) return;
        if (effective.getOrDefault(user, "").isBlank())
            throw new IllegalStateException("Required administrator identity is missing: " + user);
        if (effective.getOrDefault(hash, "").isBlank() && effective.getOrDefault(password, "").isBlank())
            throw new IllegalStateException("Required administrator credential is missing: " + hash);
    }

    static void validateDatabaseUrl(String value) {
        try {
            if (value == null || !value.startsWith("jdbc:mysql://")) throw new IllegalArgumentException();
            java.net.URI uri = java.net.URI.create(value.substring(5));
            if (uri.getHost() == null || uri.getRawUserInfo() != null
                    || uri.getPort() == 0 || uri.getPort() > 65535
                    || uri.getPath() == null || uri.getPath().length() < 2)
                throw new IllegalArgumentException();
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("A valid non-sensitive MySQL connection URL is required before startup");
        }
    }

    static void validateRequiredCredentials(Map<String, String> effective, Properties aliases) {
        for (String key : java.util.List.of("MYSQL_PASSWORD", "DB_PASSWORD", "APP_MYSQL_PASSWORD")) {
            if (aliases.containsKey(key) && effective.getOrDefault(key, "").isBlank())
                throw new IllegalStateException("Required database credential is missing: " + key);
        }
        if (aliases.containsKey("GRPC_SHARED_SECRET")) {
            String shared=SharedGrpcSecret.require(effective.get("GRPC_SHARED_SECRET"));
            for (String name : aliases.stringPropertyNames())
                if (name.contains("JWT") && name.endsWith("SECRET"))
                    SharedGrpcSecret.requireIsolated(shared, effective.get(name));
        }
        if (!"totp".equals(effective.get("AUTH_MODE"))) return;
        String key = aliases.containsKey("ADMIN_TOTP_KEY_V1")
                ? "ADMIN_TOTP_KEY_" + effective.getOrDefault("ADMIN_TOTP_ACTIVE_KEY_VERSION", "v1").toUpperCase(java.util.Locale.ROOT)
                : aliases.containsKey("ADMIN_TOTP_ENCRYPTION_KEYS") ? "ADMIN_TOTP_ENCRYPTION_KEYS"
                : aliases.containsKey("APP_ADMIN_TOTP_ENCRYPTION_KEY") ? "APP_ADMIN_TOTP_ENCRYPTION_KEY"
                : aliases.containsKey("ADMIN_TOTP_SECRET") ? "ADMIN_TOTP_SECRET" : null;
        if (key != null && effective.getOrDefault(key, "").isBlank())
            throw new IllegalStateException("Required TOTP credential is missing: " + key);
    }

    static void validateBeforeDependencies(ConfigurableEnvironment environment, Map<String, String> effective) {
        String env = effective.get("ENV");
        String mode = effective.get("AUTH_MODE");
        if (!java.util.Set.of("local", "test", "production").contains(env == null ? "" : env))
            throw new IllegalStateException("ENV must be exactly local|test|production");
        if (!java.util.Set.of("password", "totp").contains(mode == null ? "" : mode)
                || (!"local".equals(env) && !"totp".equals(mode)))
            throw new IllegalStateException("AUTH_MODE must be password|totp; test and production require TOTP");
        var reference = java.util.regex.Pattern.compile("\\$\\{([A-Z][A-Z0-9_]*)([}:])");
        for (var source : environment.getPropertySources()) {
            if (!(source instanceof EnumerablePropertySource<?> enumerable)
                    || !source.getName().startsWith("Config resource")) continue;
            for (String property : enumerable.getPropertyNames()) {
                Object raw = source.getProperty(property);
                if (!(raw instanceof String text)) continue;
                // Only the winning YAML value can require a secret.
                Object winning = null;
                for (var higher : environment.getPropertySources()) {
                    winning = higher.getProperty(property);
                    if (winning != null) break;
                }
                if (!text.equals(winning)) continue;
                var match = reference.matcher(text);
                while (match.find()) {
                    if (!"}".equals(match.group(2))) continue;
                    String key = match.group(1);
                    String secret = environment.getProperty(key);
                    if (secret == null || secret.isBlank())
                        throw new IllegalStateException("Required configuration is missing: " + key);
                }
            }
        }
    }

    public static Map<String, String> getenv() {
        Map<String, String> current = values;
        return current == null ? System.getenv() : current;
    }

    public static ConfigurableEnvironment springEnvironment() {
        ConfigurableEnvironment current = configured;
        if (current == null) {
            throw new IllegalStateException("runtime configuration must be prepared before startup");
        }
        return current;
    }

    public static ConfigurableApplicationContext run(Class<?> application, String[] args) {
        SpringApplication launcher = new SpringApplication(application);
        launcher.setEnvironment(springEnvironment());
        return launcher.run(args);
    }
}
