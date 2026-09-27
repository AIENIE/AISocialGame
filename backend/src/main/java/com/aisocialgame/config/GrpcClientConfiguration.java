package com.aisocialgame.config;

import com.aisocialgame.integration.grpc.auth.AiGrpcHmacClientInterceptor;
import com.aisocialgame.integration.grpc.auth.BillingGrpcAuthClientInterceptor;
import com.aisocialgame.integration.grpc.auth.UserGrpcAuthClientInterceptor;
import fireflychat.ai.v1.AiGatewayServiceGrpc;
import fireflychat.billing.v1.BillingBalanceServiceGrpc;
import fireflychat.billing.v1.BillingCheckinServiceGrpc;
import fireflychat.billing.v1.BillingConversionServiceGrpc;
import fireflychat.billing.v1.BillingOnboardingServiceGrpc;
import fireflychat.billing.v1.BillingQueryServiceGrpc;
import fireflychat.billing.v1.BillingRedeemCodeServiceGrpc;
import fireflychat.user.v1.UserAuthServiceGrpc;
import fireflychat.user.v1.UserBanServiceGrpc;
import fireflychat.user.v1.UserDirectoryServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.NettyChannelBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.grpc.client.GrpcChannelBuilderCustomizer;
import org.springframework.grpc.client.GrpcChannelFactory;

import java.net.URI;
import java.nio.file.Path;

/** Spring Boot gRPC channels share each service's verified TLS settings and caller interceptor. */
@Configuration(proxyBeanMethods = false)
public class GrpcClientConfiguration {
    @Bean
    GrpcChannelBuilderCustomizer<NettyChannelBuilder> canonicalGrpcTransport(Environment environment) {
        return (name, builder) -> {
            if (!name.equals("ai") && !name.equals("billing") && !name.equals("user")) {
                return;
            }
            if (Boolean.TRUE.equals(environment.getProperty(
                    "spring.grpc.client.channel." + name + ".ssl.enabled", Boolean.class))) {
                builder.useTransportSecurity();
            }
            configureBillingRetry(name, builder);
            String trust = environment.getProperty("app.grpc." + name + "-trust-cert-collection", "");
            if (!trust.isBlank()) {
                try {
                    Path file = Path.of(URI.create(trust));
                    builder.sslContext(GrpcSslContexts.forClient().trustManager(file.toFile()).build());
                } catch (Exception exception) {
                    throw new IllegalStateException("Invalid " + name + " gRPC trust certificate", exception);
                }
            }
        };
    }

    static void configureBillingRetry(String name, ManagedChannelBuilder<?> builder) {
        if ("billing".equals(name)) {
            builder.disableRetry();
        }
    }

    @Bean(value = "aiGrpcChannel", destroyMethod = "")
    ManagedChannel aiGrpcChannel(GrpcChannelFactory factory) {
        return factory.createChannel("ai");
    }

    @Bean(value = "userGrpcChannel", destroyMethod = "")
    ManagedChannel userGrpcChannel(GrpcChannelFactory factory) {
        return factory.createChannel("user");
    }

    @Bean(value = "billingGrpcChannel", destroyMethod = "")
    ManagedChannel billingGrpcChannel(GrpcChannelFactory factory) {
        return factory.createChannel("billing");
    }

    @Bean
    AiGatewayServiceGrpc.AiGatewayServiceBlockingStub aiGatewayStub(
            @Qualifier("aiGrpcChannel") ManagedChannel channel, AiGrpcHmacClientInterceptor auth) {
        return AiGatewayServiceGrpc.newBlockingStub(channel).withInterceptors(auth);
    }

    @Bean
    UserAuthServiceGrpc.UserAuthServiceBlockingStub userAuthStub(
            @Qualifier("userGrpcChannel") ManagedChannel channel, UserGrpcAuthClientInterceptor auth) {
        return UserAuthServiceGrpc.newBlockingStub(channel).withInterceptors(auth);
    }

    @Bean
    UserDirectoryServiceGrpc.UserDirectoryServiceBlockingStub userDirectoryStub(
            @Qualifier("userGrpcChannel") ManagedChannel channel, UserGrpcAuthClientInterceptor auth) {
        return UserDirectoryServiceGrpc.newBlockingStub(channel).withInterceptors(auth);
    }

    @Bean
    UserBanServiceGrpc.UserBanServiceBlockingStub userBanStub(
            @Qualifier("userGrpcChannel") ManagedChannel channel, UserGrpcAuthClientInterceptor auth) {
        return UserBanServiceGrpc.newBlockingStub(channel).withInterceptors(auth);
    }

    @Bean
    BillingBalanceServiceGrpc.BillingBalanceServiceBlockingStub billingBalanceStub(
            @Qualifier("billingGrpcChannel") ManagedChannel channel, BillingGrpcAuthClientInterceptor auth) {
        return BillingBalanceServiceGrpc.newBlockingStub(channel).withInterceptors(auth);
    }

    @Bean
    BillingQueryServiceGrpc.BillingQueryServiceBlockingStub billingQueryStub(
            @Qualifier("billingGrpcChannel") ManagedChannel channel, BillingGrpcAuthClientInterceptor auth) {
        return BillingQueryServiceGrpc.newBlockingStub(channel).withInterceptors(auth);
    }

    @Bean
    BillingCheckinServiceGrpc.BillingCheckinServiceBlockingStub billingCheckinStub(
            @Qualifier("billingGrpcChannel") ManagedChannel channel, BillingGrpcAuthClientInterceptor auth) {
        return BillingCheckinServiceGrpc.newBlockingStub(channel).withInterceptors(auth);
    }

    @Bean
    BillingRedeemCodeServiceGrpc.BillingRedeemCodeServiceBlockingStub billingRedeemStub(
            @Qualifier("billingGrpcChannel") ManagedChannel channel, BillingGrpcAuthClientInterceptor auth) {
        return BillingRedeemCodeServiceGrpc.newBlockingStub(channel).withInterceptors(auth);
    }

    @Bean
    BillingConversionServiceGrpc.BillingConversionServiceBlockingStub billingConversionStub(
            @Qualifier("billingGrpcChannel") ManagedChannel channel, BillingGrpcAuthClientInterceptor auth) {
        return BillingConversionServiceGrpc.newBlockingStub(channel).withInterceptors(auth);
    }

    @Bean
    BillingOnboardingServiceGrpc.BillingOnboardingServiceBlockingStub billingOnboardingStub(
            @Qualifier("billingGrpcChannel") ManagedChannel channel, BillingGrpcAuthClientInterceptor auth) {
        return BillingOnboardingServiceGrpc.newBlockingStub(channel).withInterceptors(auth);
    }
}
