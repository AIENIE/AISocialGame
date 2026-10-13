package com.aisocialgame.integration.grpc.client;

import com.aisocialgame.exception.ApiException;
import com.aisocialgame.integration.grpc.dto.AiChatMessageDto;
import com.aisocialgame.integration.grpc.dto.AiOcrParams;
import fireflychat.ai.v1.AiGatewayServiceGrpc;
import io.grpc.Channel;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiGrpcBudgetGateTest {
    @Test
    void allUnbudgetedInferenceIsRejectedBeforeGrpcDispatch() {
        Channel channel = mock(Channel.class);
        AiGrpcClient client = new AiGrpcClient();
        ReflectionTestUtils.setField(client, "aiStub", AiGatewayServiceGrpc.newBlockingStub(channel));
        assertBudgetUnavailable(() -> client.chatCompletions("aisocialgame", 1, "", "model",
                List.of(new AiChatMessageDto("user", "hello"))));
        assertBudgetUnavailable(() -> client.embeddings("aisocialgame", 1, "", "model", List.of("hello"), true));
        assertBudgetUnavailable(() -> client.ocrParse("aisocialgame", 1, "", "model",
                new AiOcrParams(null, "image", null, null, "TEXT")));
        verifyNoInteractions(channel);
    }

    @Test void onlyUndercoverUsesTheBoundedClientAndPreservesRequestIdentity() {
        var channel = mock(Channel.class);
        var bounded = mock(com.aisocialgame.service.credit.BoundedAiClient.class);
        var client = new AiGrpcClient();
        ReflectionTestUtils.setField(client, "aiStub", AiGatewayServiceGrpc.newBlockingStub(channel));
        ReflectionTestUtils.setField(client, "boundedClient", bounded);
        when(bounded.chat(any(), eq(19))).thenReturn(fireflychat.ai.v1.ChatCompletionsResponse.newBuilder()
                .setContent("model response").setModelKey("deepseek-flash").setPromptTokens(10).setCompletionTokens(5).build());
        var result = client.undercoverChatCompletions("aisocialgame", 85, "match:actor", "deepseek-flash",
                List.of(new AiChatMessageDto("user", "visible observation")), "job:0", 19);
        assertEquals("model response", result.content());
        var request = org.mockito.ArgumentCaptor.forClass(fireflychat.ai.v1.ChatCompletionsRequest.class);
        verify(bounded).chat(request.capture(), eq(19)); verify(bounded).requireReady();
        assertEquals("job:0", request.getValue().getRequestId());
        assertEquals("match:actor", request.getValue().getSessionId()); assertEquals(85, request.getValue().getUserId());
        assertBudgetUnavailable(() -> client.chatCompletions("aisocialgame", 85, "", "deepseek-flash", List.of()));
        verifyNoInteractions(channel);
    }

    private void assertBudgetUnavailable(Runnable operation) {
        ApiException error = assertThrows(ApiException.class, operation::run);
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.getStatus());
        assertEquals("BUDGET_UNAVAILABLE", error.getCode());
    }
}
