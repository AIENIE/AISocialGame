package com.aisocialgame.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;

class StagingTrustPolicyTest {
    @Test
    void readsTheSamePropertiesAsTheGrpcChannels() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withUserConfiguration(StagingTrustPolicy.class)
                .withPropertyValues("ENV=test",
                        "app.grpc.user-trust-cert-collection=" + StagingTrustPolicy.STAGING_ROOT,
                        "app.grpc.billing-trust-cert-collection=" + StagingTrustPolicy.STAGING_ROOT,
                        "app.grpc.ai-trust-cert-collection=" + StagingTrustPolicy.STAGING_ROOT)
                .run(context -> assertNull(context.getStartupFailure()));
    }

    @Test
    void localRequiresExportedRootForEveryGrpcClient() {
        java.nio.file.Path root = java.nio.file.Path.of("").toAbsolutePath();
        java.nio.file.Path file = root.resolve("scripts/windows/local-trust/localcert-root-ca.crt");
        if (!java.nio.file.Files.isRegularFile(file)) {
            file = root.resolve("../scripts/windows/local-trust/localcert-root-ca.crt");
        }
        String trust = file.normalize().toUri().toString();
        assertDoesNotThrow(() -> new StagingTrustPolicy("local", trust, trust, trust).validate());
        assertThrows(IllegalStateException.class,
                () -> new StagingTrustPolicy("local", trust, "", trust).validate());
    }
    @Test
    void stagingRequiresAllFixedRootsAndProductionRejectsThem() {
        assertDoesNotThrow(() -> new StagingTrustPolicy(
                "test", StagingTrustPolicy.STAGING_ROOT,
                StagingTrustPolicy.STAGING_ROOT, StagingTrustPolicy.STAGING_ROOT).validate());
        assertThrows(IllegalStateException.class, () -> new StagingTrustPolicy(
                "test", "", StagingTrustPolicy.STAGING_ROOT,
                StagingTrustPolicy.STAGING_ROOT).validate());
        assertThrows(IllegalStateException.class, () -> new StagingTrustPolicy(
                "production", StagingTrustPolicy.STAGING_ROOT, "", "").validate());
    }
}
