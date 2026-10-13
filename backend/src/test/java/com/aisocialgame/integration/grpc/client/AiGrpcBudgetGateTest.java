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
        when(bounded.chat(any(), eq(19), any(Runnable.class))).thenReturn(fireflychat.ai.v1.ChatCompletionsResponse.newBuilder()
                .setContent("model response").setModelKey("deepseek-flash").setPromptTokens(10).setCompletionTokens(5).build());
        var result = client.undercoverChatCompletions("aisocialgame", 85, "match:actor", "deepseek-flash",
                List.of(new AiChatMessageDto("user", "visible observation")), "job:0", 19);
        assertEquals("model response", result.content());
        var request = org.mockito.ArgumentCaptor.forClass(fireflychat.ai.v1.ChatCompletionsRequest.class);
        verify(bounded).chat(request.capture(), eq(19), any(Runnable.class)); verify(bounded).requireReady();
        assertEquals("job:0", request.getValue().getRequestId());
        assertTrue(request.getValue().hasThinkingEnabled()); assertTrue(request.getValue().getThinkingEnabled());
        assertEquals("match:actor", request.getValue().getSessionId()); assertEquals(85, request.getValue().getUserId());
        assertBudgetUnavailable(() -> client.chatCompletions("aisocialgame", 85, "", "deepseek-flash", List.of()));
        verifyNoInteractions(channel);
    }

    @Test void waitingForCreditDoesNotConsumeAdmissionOrPoisonTheRequestId() {
        var bounded=mock(com.aisocialgame.service.credit.BoundedAiClient.class);
        var admission=mock(com.aisocialgame.service.safety.AiCallAdmission.class);
        var client=new AiGrpcClient();
        ReflectionTestUtils.setField(client,"aiStub",AiGatewayServiceGrpc.newBlockingStub(mock(Channel.class)));
        ReflectionTestUtils.setField(client,"boundedClient",bounded);ReflectionTestUtils.setField(client,"admission",admission);
        when(bounded.chat(any(),anyInt(),any(Runnable.class)))
            .thenThrow(new ApiException(HttpStatus.BAD_REQUEST,"waiting","AI_BUDGET_BUSY",java.util.Map.of()))
            .thenAnswer(i->{i.<Runnable>getArgument(2).run();return fireflychat.ai.v1.ChatCompletionsResponse.newBuilder().setContent("ok").build();});
        assertThrows(ApiException.class,()->client.undercoverChatCompletions("aisocialgame",85,"match","deepseek-flash",List.of(),"same:0",30));
        verifyNoInteractions(admission);
        assertEquals("ok",client.undercoverChatCompletions("aisocialgame",85,"match","deepseek-flash",List.of(),"same:0",20).content());
        verify(admission,times(1)).begin(eq("same:0"),any());
        verify(admission,times(1)).finish(eq("same:0"),eq("RESPONSE"),eq(0L),eq(0L));
    }

    private void assertBudgetUnavailable(Runnable operation) {
        ApiException error = assertThrows(ApiException.class, operation::run);
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.getStatus());
        assertEquals("BUDGET_UNAVAILABLE", error.getCode());
    }
}
