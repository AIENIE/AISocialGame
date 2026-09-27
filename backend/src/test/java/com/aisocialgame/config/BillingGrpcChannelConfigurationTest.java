package com.aisocialgame.config;

import io.grpc.ManagedChannelBuilder;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

class BillingGrpcChannelConfigurationTest {

    @Test
    void disablesTransportManagedRetriesOnlyForBillingChannel() {
        ManagedChannelBuilder<?> billing = mock(ManagedChannelBuilder.class);
        ManagedChannelBuilder<?> user = mock(ManagedChannelBuilder.class);

        GrpcClientConfiguration.configureBillingRetry("billing", billing);
        GrpcClientConfiguration.configureBillingRetry("user", user);

        verify(billing).disableRetry();
        verify(user, never()).disableRetry();
    }
}
