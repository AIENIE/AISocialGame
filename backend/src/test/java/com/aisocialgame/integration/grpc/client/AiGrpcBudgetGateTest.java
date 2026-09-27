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

    private void assertBudgetUnavailable(Runnable operation) {
        ApiException error = assertThrows(ApiException.class, operation::run);
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.getStatus());
        assertEquals("BUDGET_UNAVAILABLE", error.getCode());
    }
}
