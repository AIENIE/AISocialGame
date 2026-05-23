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
        aiProxyService = new AiProxyService(aiGrpcClient, projectCreditService, appProperties, aiSafetyService);
    }

    @Test
    void embeddingsShouldMapRequestWithDefaultNormalize() {
        User user = new User();
        user.setExternalUserId(1001L);
        user.setSessionId("session-1001");
        AiEmbeddingsRequest request = new AiEmbeddingsRequest();
        request.setInput(List.of("hello", "world"));
        request.setNormalize(null);

        when(aiGrpcClient.embeddings(anyString(), anyLong(), anyString(), anyString(), eq(List.of("hello", "world")), anyBoolean()))
                .thenReturn(new AiEmbeddingsResult("model-a", 3, List.of(List.of(0.1f, 0.2f, 0.3f)), 2));

        aiProxyService.embeddings(request, user);

        verify(aiGrpcClient).embeddings("aisocialgame", 1001L, "session-1001", "default-model", List.of("hello", "world"), true);
        verify(projectCreditService).consumeProjectTokens(eq(1001L), eq(2L), eq("AI_EMBEDDINGS"), org.mockito.ArgumentMatchers.anyMap(), org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    void ocrShouldMapRequestParams() {
        User user = new User();
        user.setExternalUserId(1002L);
        user.setSessionId("session-1002");
        AiOcrRequest request = new AiOcrRequest();
        request.setImageUrl("https://example.com/a.png");
        request.setOutputType("JSON");

        when(aiGrpcClient.ocrParse(anyString(), anyLong(), anyString(), anyString(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new AiOcrResult("req-1", "ocr-a", "JSON", "{}", "{}"));

        aiProxyService.ocrParse(request, user);

        ArgumentCaptor<com.aisocialgame.integration.grpc.dto.AiOcrParams> paramsCaptor =
                ArgumentCaptor.forClass(com.aisocialgame.integration.grpc.dto.AiOcrParams.class);
        verify(aiGrpcClient).ocrParse(eq("aisocialgame"), eq(1002L), eq("session-1002"), eq("default-model"), paramsCaptor.capture());
        assertEquals("https://example.com/a.png", paramsCaptor.getValue().imageUrl());
        assertEquals("JSON", paramsCaptor.getValue().outputType());
    }

    @Test
    void chatShouldFallbackToNextAvailableTextModelWhenDefaultModelUnavailable() {
        AiChatRequest request = buildChatRequest(null, "请回复ok");
        when(aiGrpcClient.listModels(1001L)).thenReturn(List.of(
                new AiModelOptionDto(2L, "Gemini 2.5 Flash", "PackyAPI", 1, 1, "MODEL_TYPE_TEXT", false),
                new AiModelOptionDto(5L, "Gemini 2.5 Flash Image", "PackyAPI", 1, 1, "MODEL_TYPE_TEXT", true)
        ));
        when(aiGrpcClient.chatCompletions(anyString(), anyLong(), anyString(), eq("default-model"), anyList()))
                .thenThrow(new ApiException(HttpStatus.BAD_REQUEST, "模型不可用"));
        when(aiGrpcClient.chatCompletions(anyString(), anyLong(), anyString(), eq("2"), anyList()))
                .thenThrow(new ApiException(HttpStatus.BAD_REQUEST, "AI 调用失败"));
        when(aiGrpcClient.chatCompletions(anyString(), anyLong(), anyString(), eq("Gemini 2.5 Flash"), anyList()))
                .thenThrow(new ApiException(HttpStatus.BAD_REQUEST, "模型不可用"));
        when(aiGrpcClient.chatCompletions(anyString(), anyLong(), anyString(), eq("5"), anyList()))
                .thenReturn(new AiChatResult("ok", "gemini-2.5-flash-image", 4, 1));

        AiChatResult result = aiProxyService.chatByIdentity(request, 1001L, "sess-1");

        assertEquals("ok", result.content());
        assertEquals("gemini-2.5-flash-image", result.modelKey());
        verify(projectCreditService).consumeProjectTokens(eq(1001L), eq(5L), eq("AI_CHAT"), org.mockito.ArgumentMatchers.anyMap(), org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    void chatShouldFailFastWhenExplicitModelIsInvalid() {
        AiChatRequest request = buildChatRequest("invalid-model", "请回复ok");
        when(aiGrpcClient.chatCompletions(anyString(), anyLong(), anyString(), eq("invalid-model"), anyList()))
                .thenThrow(new ApiException(HttpStatus.BAD_REQUEST, "模型不可用"));

        assertThrows(ApiException.class, () -> aiProxyService.chatByIdentity(request, 1001L, "sess-1"));

        verify(aiGrpcClient, never()).listModels(anyLong());
    }

    @Test
    void chatShouldNotCallAiWhenSafetyBlocksInput() {
        AiChatRequest request = buildChatRequest(null, "M4_TEST_BLOCK");
        when(aiSafetyService.requireAllowedInput(anyString(), any(AiSafetyContext.class)))
                .thenThrow(new ApiException(HttpStatus.BAD_REQUEST, "内容未通过安全检查，请调整后再试"));

        assertThrows(ApiException.class, () -> aiProxyService.chatByIdentity(request, 1001L, "sess-1"));

        verify(aiGrpcClient, never()).chatCompletions(anyString(), anyLong(), anyString(), anyString(), anyList());
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
