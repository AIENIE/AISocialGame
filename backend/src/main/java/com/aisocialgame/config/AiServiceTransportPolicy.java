package com.aisocialgame.config;

import java.util.Map;

/** Canonical environment-to-transport authority for ai-service gRPC. */
final class AiServiceTransportPolicy {
    static final String LOCAL_TARGET = "static://localaiservice.testhut.top:22011";
    static final String TEST_TARGET = "static://aiservice.testhut.top:12011";
    static final String PRODUCTION_TARGET = "static://aiservice.seekerhut.com:12011";
    static final String STAGING_TRUST =
            "file:/run/aienie/trust/staging-root.pem";

    private AiServiceTransportPolicy() {
    }

    static Expected validateRaw(Map<String, String> environment) {
        if (environment == null) {
            throw new IllegalStateException("Runtime environment is unavailable");
        }
        String runtimeEnvironment = environment.get("ENV");
        String expectedTarget = switch (runtimeEnvironment == null ? "" : runtimeEnvironment) {
            case "local" -> LOCAL_TARGET;
            case "test" -> TEST_TARGET;
            case "production" -> PRODUCTION_TARGET;
            default -> throw new IllegalStateException("ENV must be exactly local, test, or production");
        };
        if (!expectedTarget.equals(environment.get("AI_GRPC_ADDR"))) {
            throw new IllegalStateException("AI_GRPC_ADDR is not canonical for ENV=" + runtimeEnvironment);
        }
        if (!"TLS".equals(environment.get("AI_GRPC_NEGOTIATION_TYPE"))) {
            throw new IllegalStateException("ai-service gRPC transport must remain TLS");
        }
        String trust = requirePresent(environment, "GRPC_CLIENT_AI_SECURITY_TRUST_CERT_COLLECTION");
        if ("local".equals(runtimeEnvironment)) {
            LocalGrpcTrustPolicy.require(trust);
        } else if ("test".equals(runtimeEnvironment)) {
            if (!STAGING_TRUST.equals(trust)) {
                throw new IllegalStateException(
                        "test ai-service gRPC must use the target-policy trust bundle");
            }
        } else if (!trust.isEmpty()) {
            throw new IllegalStateException("production must reject the staging trust bundle");
        }
        Expected expected = new Expected(runtimeEnvironment, expectedTarget, trust);
        if ("production".equals(runtimeEnvironment)) {
            ProductionPreactivationAuthority.requireAuthorized();
        }
        return expected;
    }

    private static String requirePresent(Map<String, String> environment, String name) {
        if (!environment.containsKey(name)) {
            throw new IllegalStateException(name + " must be present");
        }
        return environment.get(name) == null ? "" : environment.get(name);
    }

    record Expected(String runtimeEnvironment, String target, String trust) {
    }
}
