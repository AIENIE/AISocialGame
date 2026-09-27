package com.aisocialgame;

import com.aisocialgame.config.AppProperties;
import com.aisocialgame.dto.AiChatRequest;
import com.aisocialgame.dto.AiMessageRequest;
import com.aisocialgame.dto.AiEmbeddingsRequest;
import com.aisocialgame.dto.AiOcrRequest;
import com.aisocialgame.exception.ApiException;
import com.aisocialgame.integration.grpc.client.AiGrpcClient;
import com.aisocialgame.integration.grpc.dto.AiChatResult;
import com.aisocialgame.integration.grpc.dto.AiModelOptionDto;
import com.aisocialgame.integration.grpc.dto.AiEmbeddingsResult;
import com.aisocialgame.integration.grpc.dto.AiOcrResult;
import com.aisocialgame.model.User;
import com.aisocialgame.service.AiProxyService;
import com.aisocialgame.service.ProjectCreditService;
import com.aisocialgame.service.safety.AiSafetyContext;
import com.aisocialgame.service.safety.AiSafetyService;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiProxyServiceTest {

    @Mock
    private AiGrpcClient aiGrpcClient;
    @Mock
    private ProjectCreditService projectCreditService;
    @Mock
    private AiSafetyService aiSafetyService;

    private AiProxyService aiProxyService;

    @BeforeEach
    void setUp() throws Exception {
        AppProperties appProperties = new AppProperties();
        appProperties.setProjectKey("aisocialgame");
        appProperties.getAi().setDefaultModel("default-model");
        appProperties.getAi().setSystemUserId(1L);
        lenient().when(aiSafetyService.requireAllowedInput(anyString(), any(AiSafetyContext.class))).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(aiSafetyService.safeOutput(anyString(), any(AiSafetyContext.class))).thenAnswer(invocation -> invocation.getArgument(0));
        aiProxyService = new AiProxyService(aiGrpcClient, appProperties);
    }

    @Test
    void embeddingsRejectsChargeBeforeAnyUpstreamCallWithoutBudget() {
        User user = new User();
        user.setExternalUserId(1001L);
        user.setSessionId("session-1001");
        AiEmbeddingsRequest request = new AiEmbeddingsRequest();
        request.setInput(List.of("hello", "world"));
        request.setNormalize(null);

        ApiException error = assertThrows(ApiException.class, () -> aiProxyService.embeddings(request, user));
        assertEquals("BUDGET_UNAVAILABLE", error.getCode());
        org.mockito.Mockito.verifyNoInteractions(aiGrpcClient, projectCreditService);
    }

    @Test
    void ocrRejectsChargeBeforeAnyUpstreamCallWithoutBudget() {
        User user = new User();
        user.setExternalUserId(1002L);
        user.setSessionId("session-1002");
        AiOcrRequest request = new AiOcrRequest();
        request.setImageUrl("https://example.com/a.png");
        request.setOutputType("JSON");

        ApiException error = assertThrows(ApiException.class, () -> aiProxyService.ocrParse(request, user));
        assertEquals("BUDGET_UNAVAILABLE", error.getCode());
        org.mockito.Mockito.verifyNoInteractions(aiGrpcClient, projectCreditService);
    }

    @Test
    void chatRejectsChargeBeforeAnyUpstreamCallWithoutBudget() {
        AiChatRequest request = buildChatRequest(null, "hello");
        ApiException error = assertThrows(ApiException.class, () -> aiProxyService.chatByIdentity(request, 1001L, "session"));
        assertEquals("BUDGET_UNAVAILABLE", error.getCode());
        org.mockito.Mockito.verifyNoInteractions(aiGrpcClient, projectCreditService);
    }

    @Test
    void explicitModelStillRejectsBeforeAnyUpstreamCall() {
        AiChatRequest request = buildChatRequest("invalid-model", "请回复ok");
        assertThrows(ApiException.class, () -> aiProxyService.chatByIdentity(request, 1001L, "sess-1"));
        org.mockito.Mockito.verifyNoInteractions(aiGrpcClient);
    }

    @Test
    void chatDoesNotRunSafetyOrProviderWhenBudgetIsUnavailable() {
        AiChatRequest request = buildChatRequest(null, "M4_TEST_BLOCK");
        assertThrows(ApiException.class, () -> aiProxyService.chatByIdentity(request, 1001L, "sess-1"));
        org.mockito.Mockito.verifyNoInteractions(aiGrpcClient, projectCreditService);
    }

    private AiChatRequest buildChatRequest(String model, String content) {
        AiMessageRequest message = new AiMessageRequest();
        ReflectionTestUtils.setField(message, "role", "user");
        ReflectionTestUtils.setField(message, "content", content);
        AiChatRequest request = new AiChatRequest();
        request.setModel(model);
        request.setMessages(List.of(message));
        return request;
    }
}
