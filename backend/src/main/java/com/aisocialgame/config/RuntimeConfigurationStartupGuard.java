package com.aisocialgame.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Locks the final project identity and HTTP listener before listener creation. */
@Component
public final class RuntimeConfigurationStartupGuard {
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
        String expectedAddress = "test".equals(runtime) ? "0.0.0.0" : "127.0.0.1";
        String expectedPort = "test".equals(runtime) ? "20030" : "11031";
        requireExact("server.address", expectedAddress, environment.getProperty("server.address"));
        requireExact("server.port", expectedPort, environment.getProperty("server.port"));
    }

    private static void requireExact(String name, String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new IllegalStateException("Final runtime configuration is not canonical: " + name);
        }
    }
}
