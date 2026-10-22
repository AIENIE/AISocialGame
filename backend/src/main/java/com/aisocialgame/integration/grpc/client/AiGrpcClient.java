package com.aisocialgame.integration.grpc.client;

import com.aisocialgame.config.AppProperties;
import com.aisocialgame.exception.ApiException;
import com.aisocialgame.integration.grpc.auth.AiGrpcHmacClientInterceptor;
import com.aisocialgame.integration.grpc.dto.AiChatMessageDto;
import com.aisocialgame.integration.grpc.dto.AiChatResult;
import com.aisocialgame.integration.grpc.dto.AiEmbeddingsResult;
import com.aisocialgame.integration.grpc.dto.AiModelOptionDto;
import com.aisocialgame.integration.grpc.dto.AiOcrParams;
import com.aisocialgame.integration.grpc.dto.AiOcrResult;
import com.aisocialgame.service.ai.config.AiModelConfigService;
import com.aisocialgame.service.ai.config.AiModelSelection;
import fireflychat.ai.v1.AiGatewayServiceGrpc;
import fireflychat.ai.v1.ChatCompletionsRequest;
import fireflychat.ai.v1.ChatMessage;
import fireflychat.ai.v1.EmbeddingsRequest;
import fireflychat.ai.v1.ListModelsRequest;
import fireflychat.ai.v1.OcrParseRequest;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.UUID;

@Component
public class AiGrpcClient {
    @org.springframework.beans.factory.annotation.Autowired private com.aisocialgame.service.debug.LocalAiDebugRecorder debugRecorder;

    // No production override: every paid AISocialGame inference must first use
    // an authoritative budget credential. Isolated transport fixtures set this
    // field by reflection only to test gRPC cancellation and TLS mechanics.
    private boolean isolatedTransportFixture;

    @Autowired(required = false)
    private com.aisocialgame.service.credit.BoundedAiClient boundedClient;

    public void requireChatReady() {
        if (isolatedTransportFixture) return;
        if (boundedClient == null) requireBoundedBudget();
        boundedClient.requireReady();
    }

    @Autowired
    private AiGatewayServiceGrpc.AiGatewayServiceBlockingStub aiStub;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.aisocialgame.service.ai.v2.AiCallBudgetService callBudget;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.aisocialgame.service.safety.AiCallAdmission admission;

    @Autowired
    private AiModelConfigService modelConfigs;

    public List<AiModelOptionDto> listModels(long userId) {
        try {
            var response = aiStub.withDeadlineAfter(5, java.util.concurrent.TimeUnit.SECONDS).listModels(ListModelsRequest.newBuilder()
                    .setUserId(userId)
                    .build());
            return response.getModelsList().stream()
                    .map(AiModelOptionDto::from)
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
        return chatCompletions(projectKey, userId, sessionId, model, messages, UUID.randomUUID().toString(), 45);
    }

    public AiChatResult chatCompletions(String projectKey, long userId, String sessionId, String model,
                                        List<AiChatMessageDto> messages, String requestId, int deadlineSeconds) {
        return chat(projectKey, userId, sessionId, AiModelSelection.defaults(model), messages, requestId, deadlineSeconds);
    }

    public AiChatResult undercoverChatCompletions(String projectKey, long userId, String sessionId, String model,
            List<AiChatMessageDto> messages, String requestId, int deadlineSeconds) {
        return chat(projectKey, userId, sessionId, AiModelSelection.defaults(model), messages, requestId, deadlineSeconds);
    }

    public AiChatResult chatCompletions(String projectKey, long userId, String sessionId, AiModelSelection selection,
            List<AiChatMessageDto> messages, String requestId, int deadlineSeconds) {
        return chat(projectKey, userId, sessionId, selection, messages, requestId, deadlineSeconds);
    }

    public AiChatResult undercoverChatCompletions(String projectKey, long userId, String sessionId, AiModelSelection selection,
            List<AiChatMessageDto> messages, String requestId, int deadlineSeconds) {
        return chat(projectKey, userId, sessionId, selection, messages, requestId, deadlineSeconds);
    }

    private AiChatResult chat(String projectKey, long userId, String sessionId, AiModelSelection selection,
            List<AiChatMessageDto> messages, String requestId, int deadlineSeconds) {
        AppProperties.requireCanonicalProjectKey(projectKey);
        requireChatReady();
        if (selection.functionCode() != null) modelConfigs.requireUsable(selection);
        String model = selection.modelKey();
        var registered = new java.util.concurrent.atomic.AtomicBoolean();
        Runnable beforeSend = () -> {
            if (selection.functionCode() != null) modelConfigs.requireUsable(selection);
            if (admission != null) { admission.begin(requestId,com.aisocialgame.service.safety.AiCallScope.context(userId,model)); registered.set(true); }
        };
        if (isolatedTransportFixture) beforeSend.run();
        boolean answered=false;
        try {
            ChatCompletionsRequest.Builder builder = ChatCompletionsRequest.newBuilder()
                    .setRequestId(requestId)
                    .setProjectKey(AppProperties.requireCanonicalProjectKey(projectKey))
                    .setUserId(userId)
                    .setSessionId(sessionId == null ? "" : sessionId)
                    .setModel(model == null ? "" : model);
            selection.apply(builder);
            var debugContext = com.aisocialgame.service.safety.AiCallScope.context(userId, model);
            if (debugRecorder != null && debugRecorder.enabled() && debugContext.getRoomId() != null && "AI_PLAYER".equals(debugContext.getSource()))
                builder.setIncludeDebug(true);
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
            if (isolatedTransportFixture && callBudget != null) callBudget.consume();
            var stub = aiStub.withDeadlineAfter(deadlineSeconds > 0 ? deadlineSeconds : 45, java.util.concurrent.TimeUnit.SECONDS);
            var response = isolatedTransportFixture ? stub.chatCompletions(builder.build())
                    : com.aisocialgame.lab.LabCallContext.current()!=null && "REVIEW".equals(com.aisocialgame.lab.LabCallContext.current().purpose())
                        ? boundedClient.chat(builder.build(),deadlineSeconds,32768,beforeSend)
                        : boundedClient.chat(builder.build(), deadlineSeconds, beforeSend);
            if (debugRecorder != null && builder.getIncludeDebug()) debugRecorder.response(requestId, response);
            if (registered.get()) admission.finish(requestId,"RESPONSE",response.getPromptTokens(),response.getCompletionTokens());
            answered=true;
            var metadata = isolatedTransportFixture ? java.util.Map.<String,Object>of() : boundedClient.metadata(builder.build());
            return new AiChatResult(response.getContent(), response.getModelKey(), response.getPromptTokens(), response.getCompletionTokens(),
                    response.hasFinishReason() ? response.getFinishReason() : null, response.hasReasoningTokens() ? response.getReasoningTokens() : null,
                    (String) metadata.get("budgetId"), (String) metadata.get("settlementState"));
        } catch (StatusRuntimeException ex) {
            if(registered.get())admission.finish(requestId,"RPC_"+ex.getStatus().getCode().name(),null,null);
            throw toApiException(ex);
        } finally {
            if (registered.get() && !answered) admission.finish(requestId,"NO_RESPONSE",null,null);
        }
    }

    public java.util.Map<String,Object> preflight(String projectKey,long userId,AiModelSelection selection,int outputLimit) {
        requireChatReady(); modelConfigs.requireUsable(selection);
        var request=ChatCompletionsRequest.newBuilder().setRequestId("lab-preflight:"+UUID.randomUUID())
                .setProjectKey(AppProperties.requireCanonicalProjectKey(projectKey)).setUserId(userId).setModel(selection.modelKey())
                .addMessages(ChatMessage.newBuilder().setRole("user").setContent("Validate this model selection and output budget; do not execute inference."));
        selection.apply(request);
        try {
            var quote=boundedClient.preflight(request.build(),outputLimit).getPriceQuote();
            return java.util.Map.of("modelSelection",selection.toMap(),"maxOutputTokens",outputLimit,"currency",quote.getCurrency(),
                    "priceVersionId",quote.getPriceVersionId(),"maxCreditPoints",quote.getMaxCreditPoints());
        } catch(StatusRuntimeException failure) { throw toApiException(failure); }
    }

    public AiEmbeddingsResult embeddings(String projectKey,
                                         long userId,
                                         String sessionId,
                                         String model,
                                         List<String> input,
                                         boolean normalize) {
        AppProperties.requireCanonicalProjectKey(projectKey);
        requireBoundedBudget();
        String attemptId=UUID.randomUUID().toString(); boolean answered=false;
        if(admission!=null)admission.begin(attemptId,com.aisocialgame.service.safety.AiCallScope.context(userId,model));
        try {
            EmbeddingsRequest.Builder builder = EmbeddingsRequest.newBuilder()
                    .setRequestId(attemptId)
                    .setProjectKey(AppProperties.requireCanonicalProjectKey(projectKey))
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
            if(callBudget!=null)callBudget.consume();
            var response = aiStub.withDeadlineAfter(45, java.util.concurrent.TimeUnit.SECONDS).embeddings(builder.build());
            if(admission!=null)admission.finish(attemptId,"RESPONSE",response.getPromptTokens(),null);answered=true;
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
            if(admission!=null)admission.finish(attemptId,"RPC_"+ex.getStatus().getCode().name(),null,null);
            throw toApiException(ex);
        } finally {
            if(admission!=null&&!answered)admission.finish(attemptId,"NO_RESPONSE",null,null);
        }
    }

    public AiOcrResult ocrParse(String projectKey,
                                long userId,
                                String sessionId,
                                String model,
                                AiOcrParams params) {
        AppProperties.requireCanonicalProjectKey(projectKey);
        requireBoundedBudget();
        String attemptId=UUID.randomUUID().toString(); boolean answered=false;
        if(admission!=null)admission.begin(attemptId,com.aisocialgame.service.safety.AiCallScope.context(userId,model));
        try {
            OcrParseRequest.Builder builder = OcrParseRequest.newBuilder()
                    .setRequestId(attemptId)
                    .setProjectKey(AppProperties.requireCanonicalProjectKey(projectKey))
                    .setUserId(userId)
                    .setSessionId(sessionId == null ? "" : sessionId)
                    .setModel(model == null ? "" : model)
                    .setImageUrl(normalize(params.imageUrl()))
                    .setImageBase64(normalize(params.imageBase64()))
                    .setDocumentUrl(normalize(params.documentUrl()))
                    .setPages(normalize(params.pages()))
                    .setOutputType(normalizeOutputType(params.outputType()));
            if(callBudget!=null)callBudget.consume();
            var response = aiStub.withDeadlineAfter(45, java.util.concurrent.TimeUnit.SECONDS).ocrParse(builder.build());
            if(admission!=null)admission.finish(attemptId,"RESPONSE",response.getPromptTokens(),response.getCompletionTokens());answered=true;
            return new AiOcrResult(
                    response.getRequestId(),
                    response.getModelKey(),
                    response.getOutputType(),
                    response.getContent(),
                    response.getRawJson(), response.getPromptTokens(), response.getCompletionTokens(), response.getTotalTokens()
            );
        } catch (StatusRuntimeException ex) {
            if(admission!=null)admission.finish(attemptId,"RPC_"+ex.getStatus().getCode().name(),null,null);
            throw toApiException(ex);
        } finally {
            if(admission!=null&&!answered)admission.finish(attemptId,"NO_RESPONSE",null,null);
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
        ApiException failure = new ApiException(status, message);
        failure.initCause(ex);
        return failure;
    }

    private void requireBoundedBudget() {
        if (!isolatedTransportFixture) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "AI 预算能力暂不可用，请稍后再试", "BUDGET_UNAVAILABLE", java.util.Map.of());
        }
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
