package com.aisocialgame.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Verifies that all staging gRPC clients share the target-policy trust root. */
@Component
public final class StagingTrustPolicy {
    static final String STAGING_ROOT = "file:/run/aienie/trust/staging-root.pem";

    private final String runtimeEnvironment;
    private final String userTrust;
    private final String billingTrust;
    private final String aiTrust;

    public StagingTrustPolicy(
            @Value("${ENV:}") String runtimeEnvironment,
            @Value("${grpc.client.user.security.trust-cert-collection:}") String userTrust,
            @Value("${grpc.client.billing.security.trust-cert-collection:}") String billingTrust,
            @Value("${grpc.client.ai.security.trust-cert-collection:}") String aiTrust) {
        this.runtimeEnvironment = runtimeEnvironment;
        this.userTrust = userTrust;
        this.billingTrust = billingTrust;
        this.aiTrust = aiTrust;
    }

    @PostConstruct
    public void validate() {
        if ("local".equals(runtimeEnvironment)) {
            LocalGrpcTrustPolicy.require(userTrust);
            LocalGrpcTrustPolicy.require(billingTrust);
            LocalGrpcTrustPolicy.require(aiTrust);
            return;
        }
        if ("test".equals(runtimeEnvironment)) {
            if (!STAGING_ROOT.equals(userTrust)
                    || !STAGING_ROOT.equals(billingTrust)
                    || !STAGING_ROOT.equals(aiTrust)) {
                throw new IllegalStateException(
                        "staging gRPC clients must use the target-policy trust bundle");
            }
            return;
        }
        if ("production".equals(runtimeEnvironment)
                && (!userTrust.isBlank() || !billingTrust.isBlank() || !aiTrust.isBlank())) {
            throw new IllegalStateException("production must reject the staging trust bundle");
        }
    }
}
