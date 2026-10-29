package com.aisocialgame.service;

import com.aisocialgame.config.AppProperties;
import com.aisocialgame.dto.AiChatRequest;
import com.aisocialgame.exception.ApiException;
import com.aisocialgame.integration.grpc.client.AiGrpcClient;
import com.aisocialgame.integration.grpc.dto.AiChatResult;
import com.aisocialgame.integration.grpc.dto.AiModelOptionDto;
import com.aisocialgame.model.User;
import com.aisocialgame.service.safety.AiSafetyContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Chat uses the durable request and actual-settlement path.
 */
@Service
public class AiProxyService {
    private final AiGrpcClient aiGrpcClient;
    private final AppProperties appProperties;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.aisocialgame.service.safety.AiSafetyService safety;

    public AiProxyService(AiGrpcClient aiGrpcClient, AppProperties appProperties) {
        this.aiGrpcClient = aiGrpcClient;
        this.appProperties = appProperties;
    }

    public List<AiModelOptionDto> listModels(User user) {
        return aiGrpcClient.listModels(requireExternalUserId(user));
    }

    public List<AiModelOptionDto> listModelsForSystem() {
        long systemUserId = appProperties.getAi().getSystemUserId();
        if (systemUserId <= 0) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "APP_AI_SYSTEM_USER_ID 未配置");
        return aiGrpcClient.listModels(systemUserId);
    }

    public AiChatResult chat(AiChatRequest request, User user) {
        requireChargedCallReady(user);
        return chatByIdentity(request, requireExternalUserId(user), user.getSessionId());
    }

    public AiChatResult chatByIdentity(AiChatRequest request, long userId, String sessionId) {
        aiGrpcClient.requireChatReady();
        if (userId <= 0) throw new ApiException(HttpStatus.UNAUTHORIZED, "未登录");
        String model = request.getModel();
        if (model == null || model.isBlank()) model = appProperties.getAi().getDefaultModel();
        if (model == null || model.isBlank()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI 模型未配置");
        var context = com.aisocialgame.service.safety.AiCallScope.context(userId, model);
        var messages = request.getMessages().stream().map(message ->
                new com.aisocialgame.integration.grpc.dto.AiChatMessageDto(message.getRole(),
                        safety == null ? message.getContent() : safety.requireAllowedInput(message.getContent(), context))).toList();
        var response = aiGrpcClient.chatCompletions(appProperties.getProjectKey(), userId, sessionId, model, messages);
        return safety == null ? response : new AiChatResult(safety.safeOutput(response.content(), context),
                response.modelKey(), response.promptTokens(), response.completionTokens(), response.finishReason(),
                response.reasoningTokens(), response.budgetId(), response.settlementState());
    }

    public AiChatResult chatByIdentity(AiChatRequest request, long userId, String sessionId, AiSafetyContext context) {
        try (var scope = com.aisocialgame.service.safety.AiCallScope.open(context)) {
            return chatByIdentity(request, userId, sessionId);
        }
    }

    /** Synchronous admission keeps SSE's HTTP error code stable before headers commit. */
    public void requireChargedCallReady(User user) {
        requireExternalUserId(user);
        aiGrpcClient.requireChatReady();
    }

    private long requireExternalUserId(User user) {
        if (user == null || user.getExternalUserId() == null || user.getExternalUserId() <= 0)
            throw new ApiException(HttpStatus.UNAUTHORIZED, "未登录");
        return user.getExternalUserId();
    }
}
