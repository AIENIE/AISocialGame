package com.aisocialgame.integration.grpc.auth;

import fireflychat.billing.v1.BillingBalanceServiceGrpc;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BillingGrpcAuthClientInterceptorTest {
    private static final Metadata.Key<String> AUTHORIZATION_KEY =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    @Test
    void callReplacesPoisonedHeadersWithFreshCanonicalJwt() throws Exception {
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
        };
        String serverName = InProcessServerBuilder.generateName();
        Server server = InProcessServerBuilder.forName(serverName).directExecutor()
                .addService(ServerInterceptors.intercept(service, capture)).build().start();
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
            var stub = BillingBalanceServiceGrpc.newBlockingStub(ClientInterceptors.intercept(
                    channel,
                    new BillingGrpcAuthClientInterceptor(provider),
                    MetadataUtils.newAttachHeadersInterceptor(poisoned)));
            GetPublicBalanceRequest request = GetPublicBalanceRequest.newBuilder().setUserId(1L).build();

            assertEquals(7L, stub.getPublicBalance(request).getPublicPermanentCredits());

            assertEquals(1, attempts.get());
            assertEquals(1, received.size());
            assertTrue(received.get(0).startsWith("Bearer "));
            assertEquals("grpc-pay-jti-1", PayServiceCallerJwtProviderTest.parse(
                    received.get(0).substring("Bearer ".length()),
                    Instant.parse("2026-08-20T02:00:00Z"),
                    PayServiceCallerJwtProviderTest.SECRET).getBody().getId());
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
