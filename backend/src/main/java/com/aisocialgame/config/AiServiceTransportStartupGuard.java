package com.aisocialgame.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Locks final ai-service address and negotiation type to the canonical raw environment. */
@Component
public final class AiServiceTransportStartupGuard {
    private final ConfigurableEnvironment environment;
    private final ObjectProvider<RuntimeProfileTestAuthorization> testAuthorization;

    public AiServiceTransportStartupGuard(ConfigurableEnvironment environment,
                                          ObjectProvider<RuntimeProfileTestAuthorization> testAuthorization) {
        this.environment = environment;
        this.testAuthorization = testAuthorization;
    }

    @PostConstruct
    public void validate() {
        if (testAuthorization.getIfAvailable() == null) {
            validateBeforeServerCreation(environment, System.getenv());
        }
    }

    /** Runs from the packaged application's initializer, before the web server is created. */
    public static void validateBeforeServerCreation(ConfigurableEnvironment environment,
                                                    Map<String, String> rawEnvironment) {
        AiServiceTransportPolicy.Expected expected = AiServiceTransportPolicy.validateRaw(rawEnvironment);
        String address = environment.getProperty("spring.grpc.client.channel.ai.target", "");
        boolean tls = Boolean.TRUE.equals(environment.getProperty(
                "spring.grpc.client.channel.ai.ssl.enabled", Boolean.class));
        requireBound(rawEnvironment, "AI_GRPC_ADDR", address);
        if (!expected.target().equals(address) || !tls) {
            throw new IllegalStateException("Final ai-service gRPC target or TLS mode is not canonical");
        }
        String directTrust = environment.getProperty("app.grpc.ai-trust-cert-collection", "");
        requireBound(rawEnvironment, "GRPC_CLIENT_AI_SECURITY_TRUST_CERT_COLLECTION", directTrust);
        if (!expected.trust().equals(directTrust)) {
            throw new IllegalStateException("Final ai-service gRPC trust source is not canonical");
        }
        if (Boolean.TRUE.equals(environment.getProperty(
                "spring.grpc.client.channel.ai.bypass-certificate-validation", Boolean.class))
                || hasText(environment.getProperty("spring.grpc.client.channel.ai.ssl.bundle"))
                || hasText(environment.getProperty("spring.grpc.client.channel.ai.authority"))
                || hasText(environment.getProperty("spring.grpc.client.channel.ai.override-authority"))) {
            throw new IllegalStateException("Non-canonical ai-service TLS security override is forbidden");
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isEmpty();
    }

    private static void requireBound(Map<String, String> rawEnvironment,
                                     String name,
                                     String finalValue) {
        String raw = rawEnvironment == null ? null : rawEnvironment.get(name);
        if (raw == null || !raw.equals(finalValue)) {
            throw new IllegalStateException(
                    "Final ai-service transport configuration does not match its canonical environment source: "
                            + name);
        }
    }
}
