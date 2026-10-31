package com.aisocialgame.integration.grpc.client;

import com.aisocialgame.config.AppProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CanonicalProjectKeyGrpcClientTest {

    @Test
    void canonicalIdentityIsTheOnlyProjectKeyAcceptedByExternalRequestBuilders() {
        assertEquals(AppProperties.CANONICAL_PROJECT_KEY,
                AppProperties.requireCanonicalProjectKey(AppProperties.CANONICAL_PROJECT_KEY));

        AiGrpcClient ai = new AiGrpcClient();
        assertThrows(IllegalArgumentException.class,
                () -> ai.chatCompletions("attacker", 1L, "session", "model", List.of()));

        BillingGrpcClient billing = new BillingGrpcClient();
        assertThrows(IllegalArgumentException.class,
                () -> billing.convertPublicToProject("request", "attacker", 1L, 1L));
    }
}
