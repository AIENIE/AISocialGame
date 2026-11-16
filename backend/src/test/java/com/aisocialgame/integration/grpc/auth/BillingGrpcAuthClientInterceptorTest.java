package com.aisocialgame.integration.grpc.auth;

import com.aisocialgame.config.PayServiceJwtConfigurationValidator;
import fireflychat.billing.v1.BillingBalanceServiceGrpc;
import fireflychat.billing.v1.BillingOnboardingServiceGrpc;
import fireflychat.billing.v1.EnsureUserInitializedRequest;
import fireflychat.billing.v1.EnsureUserInitializedResponse;
import fireflychat.billing.v1.GetProjectBalanceRequest;
import fireflychat.billing.v1.GetProjectBalanceResponse;
import fireflychat.billing.v1.GetPublicBalanceRequest;
import fireflychat.billing.v1.GetPublicBalanceResponse;
import io.grpc.ClientInterceptors;
import io.grpc.Contexts;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BillingGrpcAuthClientInterceptorTest {
    private static final Metadata.Key<String> AUTHORIZATION_KEY =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    @Test
    void callsReplacePoisonedHeadersAndScopeLoginOperationsIndividually() throws Exception {
        List<String> received = new CopyOnWriteArrayList<>();
        ServerInterceptor capture = new ServerInterceptor() {
            @Override
            public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                    ServerCall<ReqT, RespT> call, Metadata headers,
                    ServerCallHandler<ReqT, RespT> next) {
                received.add(headers.get(AUTHORIZATION_KEY));
                assertEquals(1, count(headers, AUTHORIZATION_KEY));
                return Contexts.interceptCall(io.grpc.Context.current(), call, headers, next);
            }
        };
        AtomicInteger attempts = new AtomicInteger();
        var service = new BillingBalanceServiceGrpc.BillingBalanceServiceImplBase() {
            @Override
            public void getPublicBalance(GetPublicBalanceRequest request,
                                         StreamObserver<GetPublicBalanceResponse> observer) {
                attempts.incrementAndGet();
                observer.onNext(GetPublicBalanceResponse.newBuilder()
                        .setPublicPermanentCredits(7L).build());
                observer.onCompleted();
            }

            @Override
            public void getProjectBalance(GetProjectBalanceRequest request,
                                          StreamObserver<GetProjectBalanceResponse> observer) {
                observer.onNext(GetProjectBalanceResponse.getDefaultInstance());
                observer.onCompleted();
            }
        };
        var onboarding = new BillingOnboardingServiceGrpc.BillingOnboardingServiceImplBase() {
            @Override
            public void ensureUserInitialized(EnsureUserInitializedRequest request,
                                              StreamObserver<EnsureUserInitializedResponse> observer) {
                observer.onNext(EnsureUserInitializedResponse.newBuilder().setSuccess(true).build());
                observer.onCompleted();
            }
        };
        String serverName = InProcessServerBuilder.generateName();
        Server server = InProcessServerBuilder.forName(serverName).directExecutor()
                .addService(ServerInterceptors.intercept(service, capture))
                .addService(ServerInterceptors.intercept(onboarding, capture)).build().start();
        ManagedChannel channel = InProcessChannelBuilder.forName(serverName).directExecutor()
                .disableRetry().build();
        try {
            PayServiceCallerJwtProviderTest.MutableClock clock =
                    new PayServiceCallerJwtProviderTest.MutableClock(
                            Instant.parse("2026-08-20T02:00:00Z"));
            AtomicInteger sequence = new AtomicInteger();
            PayServiceCallerJwtProvider provider = new PayServiceCallerJwtProvider(
                    PayServiceCallerJwtProviderTest.validProperties(), clock,
                    () -> "grpc-pay-jti-" + sequence.incrementAndGet());
            Metadata poisoned = new Metadata();
            poisoned.put(AUTHORIZATION_KEY, "Bearer stale-static-token");
            var intercepted = ClientInterceptors.intercept(
                    channel,
                    new BillingGrpcAuthClientInterceptor(provider),
                    MetadataUtils.newAttachHeadersInterceptor(poisoned));
            var stub = BillingBalanceServiceGrpc.newBlockingStub(intercepted);
            var onboardingStub = BillingOnboardingServiceGrpc.newBlockingStub(intercepted);
            GetPublicBalanceRequest request = GetPublicBalanceRequest.newBuilder().setUserId(1L).build();

            assertEquals(7L, stub.getPublicBalance(request).getPublicPermanentCredits());
            assertTrue(onboardingStub.ensureUserInitialized(EnsureUserInitializedRequest.getDefaultInstance()).getSuccess());
            stub.getProjectBalance(GetProjectBalanceRequest.getDefaultInstance());

            assertEquals(1, attempts.get());
            assertEquals(3, received.size());
            var scopes = List.of(List.of("billing.balance.read"), List.of("billing.onboarding.write"),
                    PayServiceJwtConfigurationValidator.REQUIRED_SCOPES);
            for (int index = 0; index < received.size(); index++) {
                String authorization = received.get(index);
                assertTrue(authorization.startsWith("Bearer "));
                var claims = PayServiceCallerJwtProviderTest.parse(authorization.substring("Bearer ".length()),
                        clock.instant(), PayServiceCallerJwtProviderTest.SECRET).getBody();
                assertEquals("grpc-pay-jti-" + (index + 1), claims.getId());
                assertEquals(scopes.get(index), claims.get("scopes", List.class));
            }
        } finally {
            channel.shutdownNow();
            server.shutdownNow();
        }
    }

    private static int count(Metadata metadata, Metadata.Key<String> key) {
        Iterable<String> values = metadata.getAll(key);
        if (values == null) {
            return 0;
        }
        int count = 0;
        for (String ignored : values) {
            count++;
        }
        return count;
    }
}
