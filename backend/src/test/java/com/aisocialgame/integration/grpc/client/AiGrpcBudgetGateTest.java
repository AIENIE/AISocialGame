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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void chatUsesTheBoundedClientAndPreservesRequestIdentity(boolean undercover) {
        var channel = mock(Channel.class);
        var bounded = mock(com.aisocialgame.service.credit.BoundedAiClient.class);
        var client = new AiGrpcClient();
        ReflectionTestUtils.setField(client, "aiStub", AiGatewayServiceGrpc.newBlockingStub(channel));
        ReflectionTestUtils.setField(client, "boundedClient", bounded);
        when(bounded.chat(any(), eq(19), any(Runnable.class))).thenReturn(fireflychat.ai.v1.ChatCompletionsResponse.newBuilder()
                .setContent("model response").setModelKey("deepseek-flash").setPromptTokens(10).setCompletionTokens(5).build());
        when(bounded.metadata(any())).thenReturn(java.util.Map.of("budgetId", "budget", "settlementState", "SETTLED"));
        var messages = List.of(new AiChatMessageDto("user", "visible observation"));
        var result = undercover
                ? client.undercoverChatCompletions("aisocialgame", 85, "match:actor", "deepseek-flash", messages, "job:0", 19)
                : client.chatCompletions("aisocialgame", 85, "match:actor", "deepseek-flash", messages, "job:0", 19);
        assertEquals("model response", result.content());
        assertEquals("budget", result.budgetId()); assertEquals("SETTLED", result.settlementState());
        var request = org.mockito.ArgumentCaptor.forClass(fireflychat.ai.v1.ChatCompletionsRequest.class);
        verify(bounded).chat(request.capture(), eq(19), any(Runnable.class)); verify(bounded).requireReady();
        assertEquals("job:0", request.getValue().getRequestId());
        assertFalse(request.getValue().hasThinkingEnabled());
        assertEquals(fireflychat.ai.v1.ThinkingEffort.THINKING_EFFORT_UNSPECIFIED, request.getValue().getThinkingEffort());
        assertEquals("match:actor", request.getValue().getSessionId()); assertEquals(85, request.getValue().getUserId());
        verifyNoInteractions(channel);
    }

    @Test void localRoomRequestsOptInAndPersistTheOriginalProtoBeforeParsing() {
        var bounded = mock(com.aisocialgame.service.credit.BoundedAiClient.class);
        var debug = mock(com.aisocialgame.service.debug.LocalAiDebugRecorder.class);
        when(debug.enabled()).thenReturn(true);
        var response = fireflychat.ai.v1.ChatCompletionsResponse.newBuilder().setContent("invalid original text")
                .setDebugInfo(fireflychat.ai.v1.ChatDebugInfo.newBuilder().setReasoningText("original thought")).build();
        when(bounded.chat(any(), anyInt(), any(Runnable.class))).thenReturn(response);
        var client = new AiGrpcClient(); ReflectionTestUtils.setField(client, "aiStub", AiGatewayServiceGrpc.newBlockingStub(mock(Channel.class)));
        ReflectionTestUtils.setField(client, "boundedClient", bounded); ReflectionTestUtils.setField(client, "debugRecorder", debug);
        try (var scope = com.aisocialgame.service.safety.AiCallScope.open(com.aisocialgame.service.safety.AiSafetyContext.source("AI_PLAYER").room("room", "undercover"))) {
            client.undercoverChatCompletions("aisocialgame", 85, "session", "model", List.of(new AiChatMessageDto("user", "input")), "job:0", 19);
        }
        client.undercoverChatCompletions("aisocialgame", 85, "session", "model", List.of(), "unscoped:0", 19);
        var requests = org.mockito.ArgumentCaptor.forClass(fireflychat.ai.v1.ChatCompletionsRequest.class);
        verify(bounded, times(2)).chat(requests.capture(), eq(19), any(Runnable.class));
        assertTrue(requests.getAllValues().getFirst().getIncludeDebug()); assertFalse(requests.getAllValues().getLast().hasIncludeDebug());
        verify(debug).response("job:0", response); verify(debug, never()).response(eq("unscoped:0"), any());
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

    @Test void thinkingSelectionsAreAppliedBeforeBudgetPreparationAndRecheckedBeforeSend() {
        var bounded = mock(com.aisocialgame.service.credit.BoundedAiClient.class);
        var configs = mock(com.aisocialgame.service.ai.config.AiModelConfigService.class);
        var client = new AiGrpcClient();
        ReflectionTestUtils.setField(client,"boundedClient",bounded);
        ReflectionTestUtils.setField(client,"modelConfigs",configs);
        ReflectionTestUtils.setField(client,"aiStub",AiGatewayServiceGrpc.newBlockingStub(mock(Channel.class)));
        when(bounded.chat(any(),anyInt(),any(Runnable.class))).thenAnswer(call -> {
            call.<Runnable>getArgument(2).run();
            return fireflychat.ai.v1.ChatCompletionsResponse.newBuilder().setContent("ok").build();
        });
        for (var mode : com.aisocialgame.service.ai.config.AiModelSelection.ThinkingMode.values()) {
            var selection = new com.aisocialgame.service.ai.config.AiModelSelection(com.aisocialgame.service.ai.config.AiFunctionCode.UNDERCOVER,
                    "selected-model", mode, mode == com.aisocialgame.service.ai.config.AiModelSelection.ThinkingMode.ENABLED ? "HIGH" : null, 3);
            client.undercoverChatCompletions("aisocialgame",1,"session",selection,List.of(),mode.name(),30);
            verify(configs,times(2)).requireUsable(selection);
        }
        var requests = org.mockito.ArgumentCaptor.forClass(fireflychat.ai.v1.ChatCompletionsRequest.class);
        verify(bounded,times(3)).chat(requests.capture(),eq(30),any(Runnable.class));
        var values = requests.getAllValues();
        assertFalse(values.get(0).hasThinkingEnabled());
        assertTrue(values.get(1).hasThinkingEnabled()); assertTrue(values.get(1).getThinkingEnabled());
        assertEquals(fireflychat.ai.v1.ThinkingEffort.THINKING_EFFORT_HIGH,values.get(1).getThinkingEffort());
        assertTrue(values.get(2).hasThinkingEnabled()); assertFalse(values.get(2).getThinkingEnabled());
        assertTrue(values.stream().allMatch(r -> r.getModel().equals("selected-model")));
    }

    @Test void invalidSnapshotNeverPreparesABudgetOrDispatches() {
        var bounded = mock(com.aisocialgame.service.credit.BoundedAiClient.class);
        var configs = mock(com.aisocialgame.service.ai.config.AiModelConfigService.class);
        var client = new AiGrpcClient();
        ReflectionTestUtils.setField(client,"boundedClient",bounded); ReflectionTestUtils.setField(client,"modelConfigs",configs);
        var selection = new com.aisocialgame.service.ai.config.AiModelSelection(com.aisocialgame.service.ai.config.AiFunctionCode.UNDERCOVER,
                "removed",com.aisocialgame.service.ai.config.AiModelSelection.ThinkingMode.DEFAULT,null,0);
        doThrow(new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"invalid","AI_MODEL_CONFIG_INVALID",java.util.Map.of())).when(configs).requireUsable(selection);
        assertEquals("AI_MODEL_CONFIG_INVALID",assertThrows(ApiException.class,()->client.undercoverChatCompletions("aisocialgame",1,"s",selection,List.of(),"id",30)).getCode());
        verify(bounded).requireReady(); verifyNoMoreInteractions(bounded);
    }
}
