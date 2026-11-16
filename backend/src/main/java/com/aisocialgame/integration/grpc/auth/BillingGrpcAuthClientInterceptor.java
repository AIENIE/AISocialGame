package com.aisocialgame.integration.grpc.auth;

import fireflychat.billing.v1.BillingBalanceServiceGrpc;
import fireflychat.billing.v1.BillingOnboardingServiceGrpc;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class BillingGrpcAuthClientInterceptor implements ClientInterceptor {
    private static final Metadata.Key<String> AUTHORIZATION_KEY =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
    private static final Map<String, String> METHOD_SCOPES = Map.of(
            BillingOnboardingServiceGrpc.getEnsureUserInitializedMethod().getFullMethodName(), "billing.onboarding.write",
            BillingBalanceServiceGrpc.getGetPublicBalanceMethod().getFullMethodName(), "billing.balance.read"
    );

    private final PayServiceCallerJwtProvider tokenProvider;

    public BillingGrpcAuthClientInterceptor(PayServiceCallerJwtProvider tokenProvider) {
        this.tokenProvider = tokenProvider;
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(MethodDescriptor<ReqT, RespT> method,
                                                               CallOptions callOptions,
                                                               Channel next) {
        String scope = METHOD_SCOPES.get(method.getFullMethodName());
        return new ForwardingClientCall.SimpleForwardingClientCall<>(next.newCall(method, callOptions)) {
            @Override
            public void start(Listener<RespT> responseListener, Metadata headers) {
                headers.discardAll(AUTHORIZATION_KEY);
                String token = scope == null ? tokenProvider.currentToken() : tokenProvider.currentToken(scope);
                headers.put(AUTHORIZATION_KEY, "Bearer " + token);
                super.start(responseListener, headers);
            }
        };
    }
}
