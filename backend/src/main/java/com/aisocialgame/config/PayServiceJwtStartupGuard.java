package com.aisocialgame.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Map;

/** Validates final bound PayService caller settings before the web listener is created. */
@Component
public class PayServiceJwtStartupGuard {
    private static final String PREFIX = "APP_EXTERNAL_PAYSERVICE_JWT_";

    private final AppProperties appProperties;
    private final ConfigurableEnvironment environment;
    private final ObjectProvider<RuntimeProfileTestAuthorization> testAuthorization;

    public PayServiceJwtStartupGuard(AppProperties appProperties,
                                     ConfigurableEnvironment environment,
                                     ObjectProvider<RuntimeProfileTestAuthorization> testAuthorization) {
        this.appProperties = appProperties;
        this.environment = environment;
        this.testAuthorization = testAuthorization;
    }

    @PostConstruct
    public void validate() {
        boolean testAuthorized = testAuthorization.getIfAvailable() != null;
        validateFinalConfiguration(
                appProperties,
                System.getenv(),
                environment.getActiveProfiles(),
                environment.getDefaultProfiles(),
                testAuthorized);
        if (!testAuthorized) {
            validatePayTransport(environment, System.getenv());
        }
    }

    /** Runs from the packaged application's initializer, before the web server is created. */
    public static void validateBeforeServerCreation(ConfigurableEnvironment environment,
                                                    Map<String, String> rawEnvironment) {
        AppProperties.External external = Binder.get(environment)
                .bind("app.external", Bindable.of(AppProperties.External.class))
                .orElseThrow(() -> new IllegalStateException(
                        "Final PayService caller JWT configuration is unavailable"));
        AppProperties properties = new AppProperties();
        properties.setExternal(external);
        validateFinalConfiguration(
                properties,
                rawEnvironment,
                environment.getActiveProfiles(),
                environment.getDefaultProfiles(),
                false);
        validatePayTransport(environment, rawEnvironment);
    }

    static void validateFinalConfiguration(AppProperties properties,
                                           Map<String, String> rawEnvironment,
                                           String[] activeProfiles,
                                           String[] defaultProfiles,
                                           boolean testAuthorized) {
        validateFinalConfiguration(properties, rawEnvironment, activeProfiles, defaultProfiles,
                testAuthorized, System.getProperty("os.name", ""));
    }

    static void validateFinalConfiguration(AppProperties properties,
                                           Map<String, String> rawEnvironment,
                                           String[] activeProfiles,
                                           String[] defaultProfiles,
                                           boolean testAuthorized,
                                           String osName) {
        if (testAuthorized) {
            return;
        }
        boolean windowsLocal = isWindowsLocal(rawEnvironment, activeProfiles, osName);
        if (activeProfiles != null && activeProfiles.length != 0 && !windowsLocal) {
            throw new IllegalStateException("Spring active profiles are forbidden for AISocialGame runtime");
        }
        if (defaultProfiles == null || !Arrays.equals(defaultProfiles, new String[]{"default"})) {
            throw new IllegalStateException("Spring default profile must remain exactly default");
        }
        if (properties == null || properties.getExternal() == null
                || !properties.getExternal().isGrpcAuthRequired()) {
            throw new IllegalStateException("External gRPC authentication must remain enabled");
        }
        PayServiceJwtConfigurationValidator.validate(properties.getExternal());
        AppProperties.PayServiceJwt jwt = properties.getExternal().getPayserviceJwt();
        requireBound(rawEnvironment, PREFIX + "CALLER_ID", jwt.getCallerId());
        requireBound(rawEnvironment, PREFIX + "ISSUER", jwt.getIssuer());
        requireBound(rawEnvironment, PREFIX + "SERVICE", jwt.getService());
        requireBound(rawEnvironment, PREFIX + "SECRET", jwt.getSecret());
        requireBound(rawEnvironment, PREFIX + "AUDIENCE", jwt.getAudience());
        requireBound(rawEnvironment, PREFIX + "ROLE", jwt.getRole());
        requireBound(rawEnvironment, PREFIX + "TTL_SECONDS", Long.toString(jwt.getTtlSeconds()));
        requireBound(rawEnvironment, PREFIX + "SCOPES", jwt.getScopes());
        requireBound(rawEnvironment, "APP_EXTERNAL_GRPC_AUTH_REQUIRED", "true");
        String legacy = rawEnvironment.get("APP_EXTERNAL_PAYSERVICE_JWT");
        if (legacy != null && !legacy.isEmpty()) {
            throw new IllegalStateException("Legacy APP_EXTERNAL_PAYSERVICE_JWT must be absent or empty");
        }
    }

    private static boolean isWindowsLocal(Map<String, String> rawEnvironment,
                                          String[] activeProfiles,
                                          String osName) {
        return rawEnvironment != null
                && activeProfiles != null
                && Arrays.equals(activeProfiles, new String[]{"local"})
                && osName != null
                && osName.startsWith("Windows")
                && "local".equals(rawEnvironment.get("ENV"))
                && "local".equals(rawEnvironment.get("APP_ENV"))
                && "windows-local".equals(rawEnvironment.get("AIENIE_RUNTIME_PLANE"))
                && "local".equals(rawEnvironment.get("SPRING_PROFILES_ACTIVE"))
                && "127.0.0.1".equals(rawEnvironment.get("SERVER_ADDRESS"));
    }

    private static void requireBound(Map<String, String> rawEnvironment,
                                     String name,
                                     String finalValue) {
        String raw = rawEnvironment == null ? null : rawEnvironment.get(name);
        if (raw == null || !raw.equals(finalValue)) {
            throw new IllegalStateException(
                    "Final PayService caller JWT configuration does not match its canonical environment source: "
                            + name);
        }
    }

    static void validatePayTransport(ConfigurableEnvironment environment,
                                     Map<String, String> rawEnvironment) {
        PayServiceTransportPolicy.Expected expected = PayServiceTransportPolicy.validateRaw(rawEnvironment);
        String address = environment.getProperty("spring.grpc.client.channel.billing.target", "");
        boolean tls = Boolean.TRUE.equals(environment.getProperty(
                "spring.grpc.client.channel.billing.ssl.enabled", Boolean.class));
        requireBound(rawEnvironment, "BILLING_GRPC_ADDR", address);
        if (!expected.target().equals(address) || !tls) {
            throw new IllegalStateException("Final PayService gRPC target or TLS mode is not canonical");
        }
        Boolean plaintextEnabled = environment.getProperty(
                "app.external.payservice-plaintext-enabled", Boolean.class);
        requireBound(rawEnvironment, "BILLING_GRPC_PLAINTEXT_ENABLED",
                Boolean.toString(Boolean.TRUE.equals(plaintextEnabled)));
        if (Boolean.TRUE.equals(plaintextEnabled)) {
            throw new IllegalStateException("PayService plaintext transport is forbidden");
        }
        String finalTrust = environment.getProperty("app.grpc.billing-trust-cert-collection", "");
        requireOptionalBound(
                rawEnvironment,
                "GRPC_CLIENT_BILLING_SECURITY_TRUST_CERT_COLLECTION",
                finalTrust);
        if (!expected.trust().equals(finalTrust)) {
            throw new IllegalStateException("Final PayService gRPC trust source is not canonical");
        }
        if (Boolean.TRUE.equals(environment.getProperty(
                "spring.grpc.client.channel.billing.bypass-certificate-validation", Boolean.class))
                || hasText(environment.getProperty("spring.grpc.client.channel.billing.ssl.bundle"))
                || hasText(environment.getProperty("spring.grpc.client.channel.billing.authority"))
                || hasText(environment.getProperty("spring.grpc.client.channel.billing.override-authority"))) {
            throw new IllegalStateException("Non-canonical PayService TLS security override is forbidden");
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isEmpty();
    }

    private static void requireOptionalBound(Map<String, String> rawEnvironment,
                                             String name,
                                             String finalValue) {
        String raw = rawEnvironment == null ? null : rawEnvironment.get(name);
        if (raw == null ? finalValue != null && !finalValue.isEmpty() : !raw.equals(finalValue)) {
            throw new IllegalStateException(
                    "Final PayService transport configuration does not match its canonical environment source: "
                            + name);
        }
    }
}
