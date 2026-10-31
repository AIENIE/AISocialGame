package com.aisocialgame.integration.grpc.client;

import com.aisocialgame.config.AppProperties;
import com.aisocialgame.exception.ApiException;
import com.aisocialgame.integration.grpc.dto.AiChatMessageDto;
import com.aisocialgame.integration.grpc.dto.AiChatResult;
import com.aisocialgame.integration.grpc.dto.AiModelOptionDto;
import com.aisocialgame.service.ai.config.AiModelConfigService;
import com.aisocialgame.service.ai.config.AiModelSelection;
import fireflychat.ai.v1.AiGatewayServiceGrpc;
import fireflychat.ai.v1.ChatCompletionsRequest;
import fireflychat.ai.v1.ChatMessage;
import fireflychat.ai.v1.ListModelsRequest;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

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
    @Autowired(required = false)
    private com.aisocialgame.service.ai.v2.AiValidationBudgetService validationBudgets;
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

    /** Result/usage recovery does not run model admission, prepare a request, or invoke inference. */
    public AiChatResult recoverChatCompletions(String projectKey,long userId,String requestId) {
        AppProperties.requireCanonicalProjectKey(projectKey);
        if (boundedClient == null) requireBoundedBudget();
        var response=boundedClient.recoverOriginal(projectKey,userId,requestId);
        var request=ChatCompletionsRequest.newBuilder().setProjectKey(projectKey).setUserId(userId).setRequestId(requestId).build();
        var metadata=boundedClient.metadata(request);
        return new AiChatResult(response.getContent(),response.getModelKey(),response.getPromptTokens(),response.getCompletionTokens(),
                response.hasFinishReason()?response.getFinishReason():null,response.hasReasoningTokens()?response.getReasoningTokens():null,
                (String)metadata.get("budgetId"),(String)metadata.get("settlementState"));
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
            if (validationBudgets != null)
                validationBudgets.requireCallAllowed(com.aisocialgame.service.safety.AiCallScope.context(userId, model).getRoomId());
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
            boolean coach=com.aisocialgame.lab.LabCallContext.current()!=null
                    && java.util.Set.of("REVIEW","MANUAL_REVIEW","TRAINING_COACH","TRAINING_JUDGE").contains(com.aisocialgame.lab.LabCallContext.current().purpose());
            if (coach) builder.setStreamTimeouts(fireflychat.ai.v1.ChatStreamTimeouts.newBuilder()
                    .setFirstTextMs(com.aisocialgame.lab.LabCoachLimits.FIRST_TEXT_MS)
                    .setIdleMs(com.aisocialgame.lab.LabCoachLimits.IDLE_MS)
                    .setTotalMs(com.aisocialgame.lab.LabCoachLimits.TOTAL_SECONDS * 1000));
            var stub = aiStub.withDeadlineAfter(deadlineSeconds > 0 ? deadlineSeconds : 45, java.util.concurrent.TimeUnit.SECONDS);
            var response = isolatedTransportFixture ? stub.chatCompletions(builder.build())
                    : coach
                        ? boundedClient.chat(builder.build(),deadlineSeconds,com.aisocialgame.lab.LabCoachLimits.MAX_OUTPUT_TOKENS,beforeSend)
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

}
