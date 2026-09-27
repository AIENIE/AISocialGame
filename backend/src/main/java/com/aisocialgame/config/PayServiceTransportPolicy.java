package com.aisocialgame.config;

import java.util.Map;

/** Canonical environment-to-transport authority for PayService gRPC. */
final class PayServiceTransportPolicy {
    static final String LOCAL_TARGET = "static://localpayservice.testhut.top:22021";
    static final String TEST_TARGET = "static://payservice.testhut.top:12021";
    static final String PRODUCTION_TARGET = "static://payservice.seekerhut.com:12021";
    static final String STAGING_TRUST =
            "file:/run/aienie/trust/staging-root.pem";

    private PayServiceTransportPolicy() {
    }

    static Expected validateRaw(Map<String, String> environment) {
        if (environment == null) {
            throw new IllegalStateException("Runtime environment is unavailable");
        }
        String runtimeEnvironment = environment.get("ENV");
        String target = switch (runtimeEnvironment == null ? "" : runtimeEnvironment) {
            case "local" -> LOCAL_TARGET;
            case "test" -> TEST_TARGET;
            case "production" -> PRODUCTION_TARGET;
            default -> throw new IllegalStateException("ENV must be exactly local, test, or production");
        };
        requireExact(environment, "BILLING_GRPC_ADDR", target);
        requireExact(environment, "BILLING_GRPC_NEGOTIATION_TYPE", "TLS");
        requireExact(environment, "BILLING_GRPC_PLAINTEXT_ENABLED", "false");
        String trust = requirePresent(environment, "GRPC_CLIENT_BILLING_SECURITY_TRUST_CERT_COLLECTION");
        if ("local".equals(runtimeEnvironment)) {
            LocalGrpcTrustPolicy.require(trust);
        } else if ("test".equals(runtimeEnvironment)) {
            if (!STAGING_TRUST.equals(trust)) {
                throw new IllegalStateException(
                        "test PayService gRPC must use the target-policy trust bundle");
            }
        } else if (!trust.isEmpty()) {
            throw new IllegalStateException("production must reject the staging trust bundle");
        }
        Expected expected = new Expected(runtimeEnvironment, target, trust);
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

    private static void requireExact(Map<String, String> environment, String name, String expected) {
        if (!expected.equals(environment.get(name))) {
            throw new IllegalStateException(name + " is not canonical for ENV=" + environment.get("ENV"));
        }
    }

    record Expected(String runtimeEnvironment, String target, String trust) {
    }
}
