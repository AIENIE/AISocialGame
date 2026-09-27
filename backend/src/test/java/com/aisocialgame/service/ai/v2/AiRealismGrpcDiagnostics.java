package com.aisocialgame.service.ai.v2;

import com.aisocialgame.integration.grpc.client.AiGrpcClient;
import fireflychat.ai.v1.AiGatewayServiceGrpc;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.ForwardingClientCallListener;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Test-only observation of an already authorized RPC; never starts or retries a call.
 * Install once, reset immediately before each journaled attempt, then read snapshot()
 * after that attempt. No request, headers, raw status description or upstream text is retained.
 */
public final class AiRealismGrpcDiagnostics implements ClientInterceptor {
    private static final Metadata.Key<String> ERROR_SOURCE = ascii("x-aienie-error-source");
    private static final Metadata.Key<String> UPSTREAM_HTTP_STATUS = ascii("x-aienie-upstream-http-status");
    private static final Metadata.Key<byte[]> UPSTREAM_MESSAGE = Metadata.Key.of(
            "x-aienie-upstream-error-message-bin", Metadata.BINARY_BYTE_MARSHALLER);
    private static final Set<String> METHODS = Set.of(
            "ListModels", "ChatCompletions", "ChatCompletionsStream", "Embeddings", "OcrParse");
    private static final byte[] INSUFFICIENT_BALANCE = "Insufficient balance".getBytes(StandardCharsets.UTF_8);
    private final AtomicReference<CaptureWindow> current = new AtomicReference<>(new CaptureWindow());

    /** Preserves the existing channel, HMAC interceptor and stub options. Performs no RPC. */
    public static AiRealismGrpcDiagnostics install(AiGrpcClient client) {
        Object field = ReflectionTestUtils.getField(client, "aiStub");
        if (!(field instanceof AiGatewayServiceGrpc.AiGatewayServiceBlockingStub stub)) {
            throw new IllegalStateException("AI_DIAGNOSTIC_STUB_UNAVAILABLE");
        }
        AiRealismGrpcDiagnostics diagnostics = new AiRealismGrpcDiagnostics();
        ReflectionTestUtils.setField(client, "aiStub", stub.withInterceptors(diagnostics));
        return diagnostics;
    }

    /** Old callbacks retain their old window and cannot contaminate the next attempt. */
    public void reset() {
        current.set(new CaptureWindow());
    }

    /** Immutable, bounded, credential-free fields suitable for the external acceptance journal. */
    public Map<String, Object> snapshot() {
        return current.get().latest.get().fields();
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
            MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
        CaptureWindow window = current.get();
        long ordinal = window.started.incrementAndGet();
        String operation = method.getBareMethodName();
        String safeMethod = METHODS.contains(operation) ? operation : "OTHER_METHOD";
        window.publish(ordinal, Map.of("rpcObserved", true, "terminalReceived", false, "method", safeMethod));
        return new ForwardingClientCall.SimpleForwardingClientCall<>(next.newCall(method, callOptions)) {
            @Override
            public void start(Listener<RespT> responseListener, Metadata headers) {
                super.start(new ForwardingClientCallListener.SimpleForwardingClientCallListener<>(responseListener) {
                    @Override
                    public void onClose(Status status, Metadata trailers) {
                        try {
                            window.publish(ordinal, terminal(safeMethod, status, trailers));
                        } catch (RuntimeException ignored) {
                            // Diagnostics must neither alter the RPC outcome nor expose parse failures.
                            window.publish(ordinal, Map.of("rpcObserved", true, "terminalReceived", true,
                                    "method", safeMethod, "grpcStatus", status.getCode().name(),
                                    "diagnosticCapture", "UNAVAILABLE"));
                        } finally {
                            super.onClose(status, trailers);
                        }
                    }
                }, headers);
            }
        };
    }

    private static Map<String, Object> terminal(String method, Status status, Metadata trailers) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("rpcObserved", true);
        fields.put("terminalReceived", true);
        fields.put("method", method);
        fields.put("grpcStatus", status.getCode().name());
        fields.put("grpcStatusCode", status.getCode().value());
        if (trailers != null) {
            boolean upstream = "upstream".equals(trailers.get(ERROR_SOURCE));
            if (upstream) fields.put("upstreamErrorSource", "upstream");
            String http = trailers.get(UPSTREAM_HTTP_STATUS);
            int httpStatus = 0;
            if (http != null) {
                if (http.matches("[1-5][0-9]{2}")) {
                    httpStatus = Integer.parseInt(http);
                    fields.put("upstreamHttpStatus", httpStatus);
                }
                else fields.put("upstreamHttpStatusInvalid", true);
            }
            byte[] message = trailers.get(UPSTREAM_MESSAGE);
            // The transport already decoded the binary trailer. Match only this exact known
            // message; neither HTTP 402 alone nor a substring is proof of insufficient balance.
            if (upstream && httpStatus >= 400 && Arrays.equals(message, INSUFFICIENT_BALANCE)) {
                fields.put("errorClassification", "INSUFFICIENT_BALANCE");
            }
        }
        return Map.copyOf(fields);
    }

    private static Metadata.Key<String> ascii(String name) {
        return Metadata.Key.of(name, Metadata.ASCII_STRING_MARSHALLER);
    }

    private record CallSnapshot(long ordinal, Map<String, Object> fields) {}

    private static final class CaptureWindow {
        private final AtomicLong started = new AtomicLong();
        private final AtomicReference<CallSnapshot> latest = new AtomicReference<>(
                new CallSnapshot(0, Map.of("rpcObserved", false, "terminalReceived", false)));

        private void publish(long ordinal, Map<String, Object> fields) {
            Map<String, Object> immutable = Map.copyOf(fields);
            latest.updateAndGet(previous -> ordinal >= previous.ordinal()
                    ? new CallSnapshot(ordinal, immutable) : previous);
        }
    }
}
