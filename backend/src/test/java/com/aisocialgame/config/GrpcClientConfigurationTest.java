package com.aisocialgame.config;

import io.grpc.ManagedChannel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GrpcClientConfigurationTest {
    private final GrpcClientConfiguration configuration = new GrpcClientConfiguration();
    private final DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();

    @Test
    void createsTlsChannelForCanonicalStaticTarget() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.grpc.client.channel.user.ssl.enabled", "true")
                .withProperty("spring.grpc.client.channel.user.target", "static://userservice.testhut.top:12001");

        ManagedChannel channel = configuration.userGrpcChannel(
                environment, beanFactory.getBeanProvider(RuntimeProfileTestAuthorization.class));
        try {
            assertEquals("userservice.testhut.top:12001", channel.authority());
        } finally {
            channel.shutdownNow();
        }
    }

    @Test
    void rejectsDisabledTlsAndNonCanonicalTargets() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.grpc.client.channel.user.ssl.enabled", "false")
                .withProperty("spring.grpc.client.channel.user.target", "static://userservice.testhut.top:12001");
        assertThrows(IllegalStateException.class, () -> configuration.userGrpcChannel(
                environment, beanFactory.getBeanProvider(RuntimeProfileTestAuthorization.class)));

        environment.withProperty("spring.grpc.client.channel.user.ssl.enabled", "true")
                .withProperty("spring.grpc.client.channel.user.target", "static://userservice.testhut.top:12001/path");
        assertThrows(IllegalStateException.class, () -> configuration.userGrpcChannel(
                environment, beanFactory.getBeanProvider(RuntimeProfileTestAuthorization.class)));
    }

    @Test
    void allowsPlaintextOnlyWithTestProfileAndTestClasspathAuthorization() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.grpc.client.channel.user.ssl.enabled", "false")
                .withProperty("spring.grpc.client.channel.user.target", "static://127.0.0.1:19001");
        beanFactory.registerSingleton("testAuthorization", new RuntimeProfileTestAuthorization() {});
        assertThrows(IllegalStateException.class, () -> configuration.userGrpcChannel(
                environment, beanFactory.getBeanProvider(RuntimeProfileTestAuthorization.class)));

        environment.setActiveProfiles("test");
        ManagedChannel channel = configuration.userGrpcChannel(
                environment, beanFactory.getBeanProvider(RuntimeProfileTestAuthorization.class));
        try {
            assertEquals("127.0.0.1:19001", channel.authority());
        } finally {
            channel.shutdownNow();
        }
    }
}
