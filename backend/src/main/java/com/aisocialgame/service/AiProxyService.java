package com.aisocialgame.service;

import com.aisocialgame.config.AppProperties;
import com.aisocialgame.dto.AiChatRequest;
import com.aisocialgame.dto.AiEmbeddingsRequest;
import com.aisocialgame.dto.AiOcrRequest;
import com.aisocialgame.exception.ApiException;
import com.aisocialgame.integration.grpc.client.AiGrpcClient;
import com.aisocialgame.integration.grpc.dto.AiChatResult;
import com.aisocialgame.integration.grpc.dto.AiEmbeddingsResult;
import com.aisocialgame.integration.grpc.dto.AiModelOptionDto;
import com.aisocialgame.integration.grpc.dto.AiOcrResult;
import com.aisocialgame.model.User;
import com.aisocialgame.service.safety.AiSafetyContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Charged AI calls stay closed until ai-service provides a durable bounded
 * execution and this project can reserve and reconcile credit atomically.
 * The old call-then-debit path has been removed so a gate change cannot
 * accidentally restore it.
 */
@Service
public class AiProxyService {
    private final AiGrpcClient aiGrpcClient;
    private final AppProperties appProperties;

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
        throw budgetUnavailable();
    }

    public AiChatResult chatByIdentity(AiChatRequest request, long userId, String sessionId) {
        throw budgetUnavailable();
    }

    public AiChatResult chatByIdentity(AiChatRequest request, long userId, String sessionId, AiSafetyContext context) {
        throw budgetUnavailable();
    }

    public AiEmbeddingsResult embeddings(AiEmbeddingsRequest request, User user) {
        requireChargedCallReady(user);
        throw budgetUnavailable();
    }

    public AiOcrResult ocrParse(AiOcrRequest request, User user) {
        requireChargedCallReady(user);
        throw budgetUnavailable();
    }

    /** Synchronous admission keeps SSE's HTTP error code stable before headers commit. */
    public void requireChargedCallReady(User user) {
        requireExternalUserId(user);
        throw budgetUnavailable();
    }

    private ApiException budgetUnavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                "AI 预算能力暂不可用，请稍后再试", "BUDGET_UNAVAILABLE", Map.of());
    }

    private long requireExternalUserId(User user) {
        if (user == null || user.getExternalUserId() == null || user.getExternalUserId() <= 0)
            throw new ApiException(HttpStatus.UNAUTHORIZED, "未登录");
        return user.getExternalUserId();
    }
}
