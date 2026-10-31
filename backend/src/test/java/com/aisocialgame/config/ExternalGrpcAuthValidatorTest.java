package com.aisocialgame.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExternalGrpcAuthValidatorTest {
    private static final String USER_SECRET = "aisocialgame-userservice-test-secret-32-bytes";
    private static final String PAY_SECRET = "aisocialgame-payservice-test-secret-32-bytes";

    @Test
    void rejectsMissingExternalAuthenticationConfiguration() {
        AppProperties properties = new AppProperties();
        properties.setExternal(null);

        assertThrows(IllegalStateException.class,
                () -> new ExternalGrpcAuthValidator(properties).validate());
    }

    @Test
    void acceptsCanonicalCallerConfiguration() {
        AppProperties properties = validProperties();
        assertDoesNotThrow(() -> new ExternalGrpcAuthValidator(properties).validate());
    }





    @Test
    void rejectsInvalidCallerConfigurationWithoutDisclosingSecret() {
        AppProperties properties = validProperties();
        properties.getExternal().getUserserviceJwt().setScopes("user.auth.session.read,user.admin.write");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new ExternalGrpcAuthValidator(properties).validate());
        assertFalse(error.getMessage().contains(USER_SECRET));
    }

    private AppProperties validProperties() {
        AppProperties properties = new AppProperties();
        properties.getExternal().getPayserviceJwt().setSecret(PAY_SECRET);
        properties.getExternal().setAiserviceHmacCaller("aisocialgame");
        properties.getExternal().setAiserviceHmacSecret("ai-service-test-secret-distinct-32-bytes");
        properties.getExternal().getUserserviceJwt().setSecret(USER_SECRET);
        return properties;
    }
}
