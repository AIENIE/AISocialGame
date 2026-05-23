package com.aisocialgame.integration.grpc.client;

import com.aisocialgame.exception.ApiException;
import com.aisocialgame.integration.grpc.auth.AiGrpcHmacClientInterceptor;
import com.aisocialgame.integration.grpc.dto.AiChatMessageDto;
import com.aisocialgame.integration.grpc.dto.AiChatResult;
import com.aisocialgame.integration.grpc.dto.AiEmbeddingsResult;
import com.aisocialgame.integration.grpc.dto.AiModelOptionDto;
import com.aisocialgame.integration.grpc.dto.AiOcrParams;
import com.aisocialgame.integration.grpc.dto.AiOcrResult;
import fireflychat.ai.v1.AiGatewayServiceGrpc;
import fireflychat.ai.v1.ChatCompletionsRequest;
import fireflychat.ai.v1.ChatMessage;
import fireflychat.ai.v1.EmbeddingsRequest;
import fireflychat.ai.v1.ListModelsRequest;
import fireflychat.ai.v1.OcrParseRequest;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.UUID;

@Component
public class AiGrpcClient {

    @GrpcClient(value = "ai", interceptors = AiGrpcHmacClientInterceptor.class)
    private AiGatewayServiceGrpc.AiGatewayServiceBlockingStub aiStub;

    public List<AiModelOptionDto> listModels(long userId) {
        try {
            var response = aiStub.listModels(ListModelsRequest.newBuilder()
                    .setUserId(userId)
                    .build());
            return response.getModelsList().stream()
                    .map(model -> new AiModelOptionDto(
                            model.getId(),
                            model.getDisplayName(),
                            model.getProvider(),
                            model.getInputRate(),
                            model.getOutputRate(),
                            model.getType().name(),
                            model.getSupportsImageInput()
                    ))
                    .toList();
        } catch (StatusRuntimeException ex) {
            throw toApiException(ex);
        }
    }

    public AiChatResult chatCompletions(String projectKey,
                                        long userId,
                                        String sessionId,
                                        String model,
                                        List<AiChatMessageDto> messages) {
        try {
            ChatCompletionsRequest.Builder builder = ChatCompletionsRequest.newBuilder()
                    .setRequestId(UUID.randomUUID().toString())
                    .setProjectKey(projectKey == null ? "" : projectKey)
                    .setUserId(userId)
                    .setSessionId(sessionId == null ? "" : sessionId)
                    .setModel(model == null ? "" : model);
            if (messages != null) {
                for (AiChatMessageDto message : messages) {
                    if (message == null) {
                        continue;
                    }
                    builder.addMessages(ChatMessage.newBuilder()
                            .setRole(message.role() == null ? "" : message.role())
                            .setContent(message.content() == null ? "" : message.content())
                            .build());
                }
            }
            var response = aiStub.chatCompletions(builder.build());
            return new AiChatResult(response.getContent(), response.getModelKey(), response.getPromptTokens(), response.getCompletionTokens());
        } catch (StatusRuntimeException ex) {
            throw toApiException(ex);
        }
    }

    public AiEmbeddingsResult embeddings(String projectKey,
                                         long userId,
                                         String sessionId,
                                         String model,
                                         List<String> input,
                                         boolean normalize) {
        try {
            EmbeddingsRequest.Builder builder = EmbeddingsRequest.newBuilder()
                    .setRequestId(UUID.randomUUID().toString())
                    .setProjectKey(projectKey == null ? "" : projectKey)
                    .setUserId(userId)
                    .setSessionId(sessionId == null ? "" : sessionId)
                    .setModel(model == null ? "" : model)
                    .setNormalize(normalize);
            if (input != null) {
                for (String item : input) {
                    if (StringUtils.hasText(item)) {
                        builder.addInput(item);
                    }
                }
            }
            var response = aiStub.embeddings(builder.build());
            List<List<Float>> vectors = response.getEmbeddingsList().stream()
                    .map(embedding -> embedding.getVectorList().stream().toList())
                    .toList();
            return new AiEmbeddingsResult(
                    response.getModelKey(),
                    response.getDimensions(),
                    vectors,
                    response.getPromptTokens()
            );
        } catch (StatusRuntimeException ex) {
            throw toApiException(ex);
        }
    }

    public AiOcrResult ocrParse(String projectKey,
                                long userId,
                                String sessionId,
                                String model,
                                AiOcrParams params) {
        try {
            OcrParseRequest.Builder builder = OcrParseRequest.newBuilder()
                    .setRequestId(UUID.randomUUID().toString())
                    .setProjectKey(projectKey == null ? "" : projectKey)
                    .setUserId(userId)
                    .setSessionId(sessionId == null ? "" : sessionId)
                    .setModel(model == null ? "" : model)
                    .setImageUrl(normalize(params.imageUrl()))
                    .setImageBase64(normalize(params.imageBase64()))
                    .setDocumentUrl(normalize(params.documentUrl()))
                    .setPages(normalize(params.pages()))
                    .setOutputType(normalizeOutputType(params.outputType()));
            var response = aiStub.ocrParse(builder.build());
            return new AiOcrResult(
                    response.getRequestId(),
                    response.getModelKey(),
                    response.getOutputType(),
                    response.getContent(),
                    response.getRawJson()
            );
        } catch (StatusRuntimeException ex) {
            throw toApiException(ex);
        }
    }

    private ApiException toApiException(StatusRuntimeException ex) {
        Status.Code code = ex.getStatus().getCode();
        HttpStatus status = switch (code) {
            case INVALID_ARGUMENT, FAILED_PRECONDITION -> HttpStatus.BAD_REQUEST;
            case UNAUTHENTICATED -> HttpStatus.UNAUTHORIZED;
            case PERMISSION_DENIED -> HttpStatus.FORBIDDEN;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case UNAVAILABLE, DEADLINE_EXCEEDED -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.BAD_GATEWAY;
        };
        String message = ex.getStatus().getDescription();
        if (message == null || message.isBlank()) {
            message = "AI 服务调用失败";
        }
        return new ApiException(status, message);
    }

    private String normalizeOutputType(String outputType) {
        if (!StringUtils.hasText(outputType)) {
            return "TEXT";
        }
        return switch (outputType.trim().toUpperCase()) {
            case "JSON" -> "JSON";
            case "MARKDOWN" -> "MARKDOWN";
            default -> "TEXT";
        };
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
