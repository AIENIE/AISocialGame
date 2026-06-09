package com.aisocialgame.integration.grpc.auth;

import com.aisocialgame.config.AppProperties;
import com.google.protobuf.MessageLite;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

@Component
public class AiGrpcHmacClientInterceptor implements ClientInterceptor {
    private static final Metadata.Key<String> CALLER_KEY =
            Metadata.Key.of("x-aienie-caller", Metadata.ASCII_STRING_MARSHALLER);
    private static final Metadata.Key<String> TS_KEY =
            Metadata.Key.of("x-aienie-ts", Metadata.ASCII_STRING_MARSHALLER);
    private static final Metadata.Key<String> NONCE_KEY =
            Metadata.Key.of("x-aienie-nonce", Metadata.ASCII_STRING_MARSHALLER);
    private static final Metadata.Key<String> BODY_SHA256_KEY =
            Metadata.Key.of("x-aienie-body-sha256", Metadata.ASCII_STRING_MARSHALLER);
    private static final Metadata.Key<String> SIGNATURE_KEY =
            Metadata.Key.of("x-aienie-signature", Metadata.ASCII_STRING_MARSHALLER);

    private final AppProperties appProperties;

    public AiGrpcHmacClientInterceptor(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(MethodDescriptor<ReqT, RespT> method,
                                                               CallOptions callOptions,
                                                               Channel next) {
        return new ForwardingClientCall.SimpleForwardingClientCall<>(next.newCall(method, callOptions)) {
            private Listener<RespT> listener;
            private Metadata pendingHeaders;
            private boolean started;
            private int pendingRequests;

            @Override
            public void start(Listener<RespT> responseListener, Metadata headers) {
                this.listener = responseListener;
                this.pendingHeaders = headers;
            }

            @Override
            public void request(int numMessages) {
                if (started) {
                    super.request(numMessages);
                } else {
                    pendingRequests += numMessages;
                }
            }

            @Override
            public void sendMessage(ReqT message) {
                ensureStarted(message);
                super.sendMessage(message);
            }

            @Override
            public void halfClose() {
                if (!started) {
                    ensureStarted(null);
                }
                super.halfClose();
            }

            private void ensureStarted(ReqT message) {
                if (started) {
                    return;
                }
                Metadata headers = pendingHeaders == null ? new Metadata() : pendingHeaders;
                String caller = appProperties.getExternal().getAiserviceHmacCaller().trim();
                String secret = appProperties.getExternal().getAiserviceHmacSecret().trim();
                String ts = String.valueOf(Instant.now().getEpochSecond());
                String nonce = UUID.randomUUID().toString();
                String fullMethod = method.getFullMethodName();
                String methodPath = fullMethod.startsWith("/") ? fullMethod : "/" + fullMethod;
                String bodySha256 = sha256Hex(requestBytes(method, message));
                String canonical = caller + "\n" + methodPath + "\n" + ts + "\n" + nonce + "\n" + bodySha256;
                String signature = sign(canonical, secret);

                headers.put(CALLER_KEY, caller);
                headers.put(TS_KEY, ts);
                headers.put(NONCE_KEY, nonce);
                headers.put(BODY_SHA256_KEY, bodySha256);
                headers.put(SIGNATURE_KEY, signature);
                super.start(listener, headers);
                started = true;
                if (pendingRequests > 0) {
                    super.request(pendingRequests);
                    pendingRequests = 0;
                }
            }
        };
    }

    private <ReqT> byte[] requestBytes(MethodDescriptor<ReqT, ?> method, ReqT message) {
        if (message == null) {
            return new byte[0];
        }
        if (message instanceof MessageLite protobuf) {
            return protobuf.toByteArray();
        }
        try (InputStream in = method.getRequestMarshaller().stream(message);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            in.transferTo(out);
            return out.toByteArray();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to serialize ai grpc request", ex);
        }
    }

    private String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to compute ai grpc request body hash", ex);
        }
    }

    private String sign(String text, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(text.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to sign ai grpc request", ex);
        }
    }
}
