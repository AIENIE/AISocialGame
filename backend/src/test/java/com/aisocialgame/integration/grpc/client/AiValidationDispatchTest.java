package com.aisocialgame.integration.grpc.client;

import com.aisocialgame.exception.ApiException;
import com.aisocialgame.service.ai.v2.AiValidationBudgetService;
import com.aisocialgame.service.credit.BoundedAiClient;
import com.aisocialgame.service.safety.AiCallAdmission;
import com.aisocialgame.service.safety.AiCallScope;
import com.aisocialgame.service.safety.AiSafetyContext;
import fireflychat.ai.v1.AiGatewayServiceGrpc;
import fireflychat.ai.v1.ChatCompletionsResponse;
import io.grpc.Channel;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiValidationDispatchTest {
    @Test void freshTransmissionChecksActualSpendBeforeConsumingAdmission() {
        var channel = mock(Channel.class);
        var bounded = mock(BoundedAiClient.class);
        var admission = mock(AiCallAdmission.class);
        var budgets = mock(AiValidationBudgetService.class);
        AiGrpcClient client = client(channel, bounded, admission, budgets);
        when(bounded.chat(any(), anyInt(), any(Runnable.class))).thenAnswer(call -> {
            call.<Runnable>getArgument(2).run();
            return ChatCompletionsResponse.newBuilder().setContent("should not send").build();
        });
        doThrow(new ApiException(HttpStatus.CONFLICT, "budget exhausted", AiValidationBudgetService.EXHAUSTED, Map.of()))
                .when(budgets).requireCallAllowed("validation-room");
        try (var scope = AiCallScope.open(AiSafetyContext.source("AI_PLAYER").room("validation-room", "werewolf"))) {
            var blocked = assertThrows(ApiException.class, () -> client.chatCompletions("aisocialgame", 1, "match:actor",
                    "deepseek-flash", List.of(), "job:0", 30));
            assertEquals(AiValidationBudgetService.EXHAUSTED, blocked.getCode());
        }
        verify(budgets).requireCallAllowed("validation-room");
        verifyNoInteractions(admission, channel);
    }

    @Test void recoveryOfAnExistingRequestDoesNotRunTheNewTransmissionGate() {
        var channel = mock(Channel.class);
        var bounded = mock(BoundedAiClient.class);
        var admission = mock(AiCallAdmission.class);
        var budgets = mock(AiValidationBudgetService.class);
        AiGrpcClient client = client(channel, bounded, admission, budgets);
        when(bounded.chat(any(), anyInt(), any(Runnable.class)))
                .thenReturn(ChatCompletionsResponse.newBuilder().setContent("saved result").build());
        try (var scope = AiCallScope.open(AiSafetyContext.source("AI_PLAYER").room("validation-room", "werewolf"))) {
            assertEquals("saved result", client.chatCompletions("aisocialgame", 1, "match:actor",
                    "deepseek-flash", List.of(), "original:0", 30).content());
        }
        verifyNoInteractions(budgets, admission, channel);
    }

    private AiGrpcClient client(Channel channel, BoundedAiClient bounded, AiCallAdmission admission, AiValidationBudgetService budgets) {
        var client = new AiGrpcClient();
        ReflectionTestUtils.setField(client, "aiStub", AiGatewayServiceGrpc.newBlockingStub(channel));
        ReflectionTestUtils.setField(client, "boundedClient", bounded);
        ReflectionTestUtils.setField(client, "admission", admission);
        ReflectionTestUtils.setField(client, "validationBudgets", budgets);
        return client;
    }
}
