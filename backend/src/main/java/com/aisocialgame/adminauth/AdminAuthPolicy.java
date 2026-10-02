package com.aisocialgame.adminauth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;

import java.util.Map;

public record AdminAuthPolicy(String environment, String authMode) {
    public static final String ENV_LOCAL = "local";
    public static final String ENV_TEST = "test";
    public static final String ENV_PRODUCTION = "production";
    public static final String MODE_PASSWORD = "password";
    public static final String MODE_TOTP = "totp";

    public AdminAuthPolicy {
        boolean validEnvironment = ENV_LOCAL.equals(environment)
                || ENV_TEST.equals(environment)
                || ENV_PRODUCTION.equals(environment);
        boolean validMode = MODE_PASSWORD.equals(authMode) || MODE_TOTP.equals(authMode);
        boolean validCombination = ENV_LOCAL.equals(environment)
                ? validMode
                : validEnvironment && MODE_TOTP.equals(authMode);
        if (!validEnvironment || !validMode || !validCombination) {
            throw new IllegalStateException("Invalid administrator authentication ENV/AUTH_MODE combination");
        }
    }

    public static AdminAuthPolicy fromEnvironment(Map<String, String> environment) {
        return new AdminAuthPolicy(environment.get("ENV"), environment.get("AUTH_MODE"));
    }

    public boolean totpRequired() {
        return MODE_TOTP.equals(authMode);
    }

    public boolean passwordOnly() {
        return MODE_PASSWORD.equals(authMode);
    }

    @Configuration
    static class Provider {
        @Bean
        @Profile("!test")
        AdminAuthPolicy systemAdminAuthPolicy() {
            return fromEnvironment(com.aienie.configpair.RuntimeConfiguration.getenv());
        }

        @Bean
        @Profile("test")
        @ConditionalOnClass(name = "org.junit.jupiter.api.Test")
        AdminAuthPolicy testAdminAuthPolicy() {
            // This compatibility bean exists only on the Maven/JUnit test classpath.
            // A packaged application started with the Spring `test` profile has no
            // policy bean and therefore fails closed instead of enabling password mode.
            return new AdminAuthPolicy(ENV_LOCAL, MODE_PASSWORD);
        }
    }
}
