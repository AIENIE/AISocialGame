package com.aisocialgame.integration.grpc.auth;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import org.springframework.stereotype.Component;

@Component
public class BillingGrpcAuthClientInterceptor implements ClientInterceptor {
    private static final Metadata.Key<String> AUTHORIZATION_KEY =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    private final PayServiceCallerJwtProvider tokenProvider;

    public BillingGrpcAuthClientInterceptor(PayServiceCallerJwtProvider tokenProvider) {
        this.tokenProvider = tokenProvider;
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(MethodDescriptor<ReqT, RespT> method,
                                                               CallOptions callOptions,
                                                               Channel next) {
        return new ForwardingClientCall.SimpleForwardingClientCall<>(next.newCall(method, callOptions)) {
            @Override
            public void start(Listener<RespT> responseListener, Metadata headers) {
                headers.discardAll(AUTHORIZATION_KEY);
                headers.put(AUTHORIZATION_KEY, "Bearer " + tokenProvider.currentToken());
                super.start(responseListener, headers);
            }
        };
    }
}
