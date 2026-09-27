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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import java.net.URI;
import java.nio.file.Path;

/** Build caller channels with the reviewed TLS trust source for each service. */
@Configuration(proxyBeanMethods = false)
public class GrpcClientConfiguration {
    private ManagedChannel channel(String name, Environment environment,
                                   ObjectProvider<RuntimeProfileTestAuthorization> testAuthorization) {
        boolean tls = Boolean.TRUE.equals(environment.getProperty(
                "spring.grpc.client.channel." + name + ".ssl.enabled", Boolean.class));
        boolean authorizedTest = environment.acceptsProfiles(Profiles.of("test"))
                && testAuthorization.getIfAvailable() != null;
        if (!tls && !authorizedTest) {
            throw new IllegalStateException(name + " gRPC TLS must be enabled");
        }
        String target = environment.getProperty("spring.grpc.client.channel." + name + ".target", "");
        URI uri = URI.create(target);
        if (!"static".equals(uri.getScheme()) || uri.getHost() == null
                || uri.getPort() <= 0 || uri.getPort() > 65535
                || uri.getUserInfo() != null
                || (uri.getRawPath() != null && !uri.getRawPath().isEmpty())
                || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalStateException("Invalid " + name + " gRPC target");
        }
        NettyChannelBuilder builder = NettyChannelBuilder.forAddress(uri.getHost(), uri.getPort());
        String trust = environment.getProperty("app.grpc." + name + "-trust-cert-collection", "");
        if (!tls) {
            if (!trust.isBlank()) {
                throw new IllegalStateException(name + " gRPC plaintext test channel cannot set a trust certificate");
            }
            builder.usePlaintext();
        } else if (trust.isBlank()) {
            builder.useTransportSecurity();
        } else {
            try {
                Path file = Path.of(URI.create(trust));
                builder.sslContext(GrpcSslContexts.forClient().trustManager(file.toFile()).build());
            } catch (Exception exception) {
                throw new IllegalStateException("Invalid " + name + " gRPC trust certificate", exception);
            }
        }
        configureBillingRetry(name, builder);
        return builder.build();
    }

    static void configureBillingRetry(String name, ManagedChannelBuilder<?> builder) {
        if ("billing".equals(name)) {
            builder.disableRetry();
        }
    }

    @Bean(value = "aiGrpcChannel", destroyMethod = "shutdown")
    ManagedChannel aiGrpcChannel(Environment environment,
                                 ObjectProvider<RuntimeProfileTestAuthorization> testAuthorization) {
        return channel("ai", environment, testAuthorization);
    }

    @Bean(value = "userGrpcChannel", destroyMethod = "shutdown")
    ManagedChannel userGrpcChannel(Environment environment,
                                   ObjectProvider<RuntimeProfileTestAuthorization> testAuthorization) {
        return channel("user", environment, testAuthorization);
    }

    @Bean(value = "billingGrpcChannel", destroyMethod = "shutdown")
    ManagedChannel billingGrpcChannel(Environment environment,
                                      ObjectProvider<RuntimeProfileTestAuthorization> testAuthorization) {
        return channel("billing", environment, testAuthorization);
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
