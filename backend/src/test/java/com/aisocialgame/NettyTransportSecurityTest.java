package com.aisocialgame;

import fireflychat.ai.v1.*;
import io.grpc.*;
import io.grpc.netty.*;
import io.grpc.stub.StreamObserver;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.file.*;
import java.security.KeyStore;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.*;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the actual unshaded HTTP/2 transport, including certificate and cancellation paths. */
class NettyTransportSecurityTest {
    @Test void trustedTlsSucceedsButUntrustedAndWrongHostnameFailBeforeApplicationWork(@TempDir Path directory) throws Exception {
        KeyStore store = certificate(directory);
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store, "test-only-password".toCharArray());
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(store);
        SslContext clientTls = GrpcSslContexts.forClient().sslProvider(SslProvider.JDK).trustManager(trust).build();
        var received = new AtomicInteger();
        var cancelled = new CountDownLatch(1);
        var server = NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
                .sslContext(GrpcSslContexts.configure(io.netty.handler.ssl.SslContextBuilder.forServer(keys), SslProvider.JDK).build())
                .addService(new AiGatewayServiceGrpc.AiGatewayServiceImplBase() {
                    @Override public void chatCompletions(ChatCompletionsRequest request, StreamObserver<ChatCompletionsResponse> response) {
                        received.incrementAndGet();
                        if (request.getRequestId().equals("blocked")) {
                            Context.current().addListener(ignored -> cancelled.countDown(), Runnable::run);
                            return;
                        }
                        response.onNext(ChatCompletionsResponse.getDefaultInstance());
                        response.onCompleted();
                    }
                }).build().start();
        try {
            var trusted = NettyChannelBuilder.forAddress("localhost", server.getPort()).sslContext(clientTls).build();
            try {
                var stub = AiGatewayServiceGrpc.newBlockingStub(trusted);
                assertNotNull(stub.withDeadlineAfter(5, TimeUnit.SECONDS).chatCompletions(ChatCompletionsRequest.getDefaultInstance()));
                var timeout = assertThrows(StatusRuntimeException.class, () -> stub.withDeadlineAfter(500, TimeUnit.MILLISECONDS)
                        .chatCompletions(ChatCompletionsRequest.newBuilder().setRequestId("blocked").build()));
                assertEquals(Status.Code.DEADLINE_EXCEEDED, timeout.getStatus().getCode());
                assertTrue(cancelled.await(3, TimeUnit.SECONDS));
            } finally { trusted.shutdownNow().awaitTermination(3, TimeUnit.SECONDS); }
            assertEquals(2, received.get());
            var wrongHost = NettyChannelBuilder.forAddress("127.0.0.1", server.getPort()).sslContext(clientTls).build();
            var untrusted = NettyChannelBuilder.forAddress("localhost", server.getPort())
                    .sslContext(GrpcSslContexts.forClient().sslProvider(SslProvider.JDK).build()).build();
            for (var channel : java.util.List.of(wrongHost, untrusted)) {
                try {
                    var failure = assertThrows(StatusRuntimeException.class, () -> AiGatewayServiceGrpc.newBlockingStub(channel)
                            .withDeadlineAfter(3, TimeUnit.SECONDS).chatCompletions(ChatCompletionsRequest.getDefaultInstance()));
                    assertEquals(Status.Code.UNAVAILABLE, failure.getStatus().getCode());
                } finally { channel.shutdownNow().awaitTermination(3, TimeUnit.SECONDS); }
            }
            assertEquals(2, received.get(), "TLS failures must not reach application handlers");
        } finally { server.shutdownNow().awaitTermination(3, TimeUnit.SECONDS); }
    }

    private KeyStore certificate(Path directory) throws Exception {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool";
        Path file = directory.resolve("server.p12");
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-genkeypair", "-alias", "test", "-keyalg", "RSA", "-storetype", "PKCS12", "-keystore", file.toString(),
                "-storepass", "test-only-password", "-keypass", "test-only-password", "-dname", "CN=localhost",
                "-ext", "SAN=dns:localhost", "-validity", "1")
                .redirectErrorStream(true).redirectOutput(directory.resolve("keytool.log").toFile()).start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) { process.destroyForcibly(); fail("TLS test certificate generation timed out"); }
        assertEquals(0, process.exitValue());
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(file)) { store.load(input, "test-only-password".toCharArray()); }
        return store;
    }
}
