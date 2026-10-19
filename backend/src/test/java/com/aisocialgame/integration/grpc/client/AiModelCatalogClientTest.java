package com.aisocialgame.integration.grpc.client;

import com.aisocialgame.exception.ApiException;
import com.aisocialgame.integration.grpc.auth.AiGrpcHmacClientInterceptor;
import com.aisocialgame.config.AppProperties;
import fireflychat.ai.v1.*;
import io.grpc.*;
import io.grpc.inprocess.*;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import static org.junit.jupiter.api.Assertions.*;

class AiModelCatalogClientTest {
    private ManagedChannel channel;
    private Server server;
    private final List<Metadata> received = new ArrayList<>();
    private boolean unsupported;
    private AiModelCatalogClient client;
    private static final String SECRET = "catalog-unit-test-signing-secret";
    @BeforeEach void start() throws Exception {
        var service = new AiGatewayServiceGrpc.AiGatewayServiceImplBase() {
            @Override public void listCallerModels(ListCallerModelsRequest request, StreamObserver<ListCallerModelsResponse> observer) {
                assertEquals(0, request.toByteArray().length);
                if (unsupported) { observer.onError(Status.UNIMPLEMENTED.asRuntimeException()); return; }
                observer.onNext(ListCallerModelsResponse.newBuilder().addModels(ModelOption.newBuilder().setId(12).setModelKey("catalog-only")
                        .setType(ModelType.MODEL_TYPE_TEXT).setThinking(ModelThinkingCapabilities.newBuilder().setDeclared(true)
                                .setSupportsEnable(true).addOptions(ThinkingOption.newBuilder().setEffort(ThinkingEffort.THINKING_EFFORT_HIGH).setLabel("高"))))
                        .addModels(ModelOption.newBuilder().setModelKey("old-server-model")).build()); observer.onCompleted();
            }
        };
        var name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name).directExecutor().intercept(new ServerInterceptor() {
            @Override public <R, S> ServerCall.Listener<R> interceptCall(ServerCall<R, S> call, Metadata headers, ServerCallHandler<R, S> next) {
                received.add(headers); return next.startCall(call, headers);
            }
        }).addService(service).build().start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        var properties = new AppProperties();
        properties.getExternal().setAiserviceHmacSecret(SECRET);
        var interceptor = new AiGrpcHmacClientInterceptor(properties);
        client = new AiModelCatalogClient(AiGatewayServiceGrpc.newBlockingStub(channel).withInterceptors(interceptor));
    }
    @AfterEach void close() { channel.shutdownNow(); server.shutdownNow(); }
    private String header(Metadata metadata, String name) { return metadata.get(Metadata.Key.of(name, Metadata.ASCII_STRING_MARSHALLER)); }
    @Test void emptyProtobufUsesExistingHmacAndPreservesOptionalCapabilityPresence() throws Exception {
        var models = client.listCallerModels();
        assertEquals("catalog-only", models.getFirst().modelKey()); assertNull(models.getFirst().thinking().defaultEnabled());
        assertNull(models.getFirst().thinking().defaultEffort()); assertNull(models.get(1).thinking());
        assertEquals("HIGH", models.getFirst().thinking().options().getFirst().effort());
        var headers = received.getFirst();
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(new byte[0]));
        assertEquals(hash, header(headers, "x-aienie-body-sha256"));
        String signed = header(headers,"x-aienie-caller") + "\n/fireflychat.ai.v1.AiGatewayService/ListCallerModels\n"
                + header(headers,"x-aienie-ts") + "\n" + header(headers,"x-aienie-nonce") + "\n" + hash;
        var mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        assertEquals(Base64.getEncoder().encodeToString(mac.doFinal(signed.getBytes(StandardCharsets.UTF_8))), header(headers,"x-aienie-signature"));
    }
    @Test void oldServerFailsExplicitlyWithoutFallingBackToUserScopedEnumeration() {
        unsupported = true;
        assertEquals("AI_MODEL_SYNC_FAILED", assertThrows(ApiException.class, client::listCallerModels).getCode());
        assertEquals(1, received.size());
    }
}
