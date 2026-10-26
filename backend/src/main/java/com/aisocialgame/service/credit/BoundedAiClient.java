package com.aisocialgame.service.credit;

import com.aisocialgame.exception.ApiException;
import com.aisocialgame.model.credit.AiCreditReservation;
import com.aisocialgame.repository.credit.AiCreditReservationRepository;
import com.aisocialgame.service.ai.v2.AiCallBudgetService;
import fireflychat.ai.v1.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
public class BoundedAiClient {
    @org.springframework.beans.factory.annotation.Autowired(required=false) private com.aisocialgame.lab.LabBudgetService labBudgets;
    @org.springframework.beans.factory.annotation.Autowired private com.aisocialgame.service.debug.LocalAiDebugRecorder debugRecorder;
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BoundedAiClient.class);
    private final AiGatewayServiceGrpc.AiGatewayServiceBlockingStub stub;
    private final AiCallSettlementService escrow;
    private final AiCreditReservationRepository reservations;
    private final AiCallBudgetService callBudget;
    private final boolean enabled;
    private final int maxOutput;
    public BoundedAiClient(AiGatewayServiceGrpc.AiGatewayServiceBlockingStub stub,AiCallSettlementService escrow,
            AiCreditReservationRepository reservations,AiCallBudgetService callBudget,
            @Value("${app.ai.budget-enabled:false}") boolean enabled,
            @Value("${app.ai.budget-max-output-tokens:8192}") int maxOutput) {
        this.stub=stub; this.escrow=escrow; this.reservations=reservations; this.callBudget=callBudget;
        this.enabled=enabled; this.maxOutput=maxOutput;
    }
    public void requireReady() {
        if(!enabled || maxOutput<=0) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"AI 预算能力暂不可用，请稍后再试","BUDGET_UNAVAILABLE",Map.of());
    }
    public ChatCompletionsResponse chat(ChatCompletionsRequest request,int deadlineSeconds) {
        return chat(request, deadlineSeconds, () -> {});
    }
    public ChatCompletionsResponse chat(ChatCompletionsRequest request,int deadlineSeconds,Runnable beforeSend) {
        return chat(request, deadlineSeconds, maxOutput, beforeSend);
    }
    public ChatCompletionsResponse chat(ChatCompletionsRequest request,int deadlineSeconds,int outputLimit,Runnable beforeSend) {
        requireReady();
        request = originalRequestFlags(request);
        return execute(request, deadlineSeconds, outputLimit, beforeSend);
    }
    // Old reservations are bound to the exact request bytes, including absence of diagnostic flags.
    private ChatCompletionsRequest originalRequestFlags(ChatCompletionsRequest request) {
        var original = reservations.findByIdentityHash(identity(request)).orElse(null);
        if (original == null || original.requestHash.equals(AiCallSettlementService.hash(request.toByteArray()))) return request;
        for (var legacy:java.util.List.of(request.toBuilder().clearIncludeDebug().build(),
                request.toBuilder().clearStreamTimeouts().build(),request.toBuilder().clearStreamTimeouts().clearIncludeDebug().build()))
            if (original.requestHash.equals(AiCallSettlementService.hash(legacy.toByteArray()))) return legacy;
        return request;
    }
    public Map<String,Object> metadata(ChatCompletionsRequest request) {
        return reservations.findByIdentityHash(identity(request)).map(r -> Map.<String,Object>of("budgetId",r.budgetId,"settlementState",r.state)).orElse(Map.of());
    }
    /** Preflight validates the actual caller/model/effort, limit and monetary quote without inference or wallet holds. */
    public PrepareCallBudgetResponse preflight(ChatCompletionsRequest request, int outputLimit) {
        requireReady();
        var budget=stub.withDeadlineAfter(5,TimeUnit.SECONDS).prepareCallBudget(PrepareCallBudgetRequest.newBuilder()
                .setChat(request).setMaxOutputTokens(outputLimit).setMaxProviderAttempts(1).setBillingCurrency("CNY").build());
        if (!budget.hasPriceQuote()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"AI 服务尚未提供金额预算","BUDGET_PRICING_UNAVAILABLE",Map.of());
        return budget;
    }
    private ChatCompletionsResponse execute(ChatCompletionsRequest request, int deadlineSeconds, int outputLimit, Runnable beforeSend) {
        var previous=reservations.findByIdentityHash(identity(request)).orElse(null);
        if (previous!=null) {
            if (!previous.requestHash.equals(AiCallSettlementService.hash(request.toByteArray())))
                throw new ApiException(HttpStatus.CONFLICT,"AI 请求标识与原内容不一致","BUDGET_REQUEST_CONFLICT",Map.of());
            return recover(previous);
        }
        if (labBudgets != null) labBudgets.checkCall();
        long remainingSeconds=Math.min(request.hasStreamTimeouts() ? 1200 : 600,Math.max(1,deadlineSeconds));
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(remainingSeconds);
        PrepareCallBudgetResponse budget;
        try {
            budget=stub.withDeadlineAfter(Math.min(5,remainingSeconds),TimeUnit.SECONDS).prepareCallBudget(PrepareCallBudgetRequest.newBuilder()
                .setChat(request).setMaxOutputTokens(outputLimit).setMaxProviderAttempts(1).setBillingCurrency("CNY").build());
        } catch (io.grpc.StatusRuntimeException failure) {
            // Preparing a budget never invokes the supplier.
            if (transientFailure(failure)) throw notSent();
            throw failure;
        }
        if (!budget.hasPriceQuote()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"AI 服务尚未提供金额预算","BUDGET_PRICING_UNAVAILABLE",Map.of());
        var claim=escrow.register(request,budget);
        if(claim.acquired()) {
            boolean executionStarted=false;
            try {
                long remaining=deadline-System.nanoTime();
                remaining -= Math.min(TimeUnit.SECONDS.toNanos(2), Math.max(0, remaining / 4));
                if(remaining<=0) throw io.grpc.Status.DEADLINE_EXCEEDED.asRuntimeException();
                beforeSend.run();
                callBudget.consume();
                remaining = deadline-System.nanoTime();
                remaining -= Math.min(TimeUnit.SECONDS.toNanos(2), Math.max(0, remaining / 4));
                if (remaining <= 0) throw io.grpc.Status.DEADLINE_EXCEEDED.asRuntimeException();
                executionStarted=true;
                var events=stub.withDeadlineAfter(remaining,TimeUnit.NANOSECONDS)
                    .chatCompletionsStream(request.toBuilder().setBudgetCredential(budget.getBudgetCredential()).build());
                // Partial text is diagnostic only. The authoritative saved result is recovered below.
                while (events.hasNext()) events.next();
            } catch(RuntimeException failure) {
                // Local admission/deadline failures have not attempted the execution RPC.
                // Preserve the local cause; the prepared request expires without a send.
                if (!executionStarted) throw failure;
                // Querying is safe; neither this path nor the worker retries inference.
                return recover(claim.reservation());
            }
        }
        return recover(claim.reservation());
    }
    private ChatCompletionsResponse recover(AiCreditReservation row) {
        BudgetCallState remote=null;
        GetBudgetCallStatusResponse authority=null;
        if (AiCallSettlementService.pending(row.state)) {
            try { authority=reconcile(row);remote=authority.getState(); }
            catch (RuntimeException unavailable) { log.info("AI result query deferred requestId={} errorType={}",row.requestId,unavailable.getClass().getSimpleName()); }
            row=reservations.findById(row.id).orElseThrow();
        }
        if ("SETTLED".equals(row.state)) return result(row);
        if ("NOT_SENT".equals(row.state) || "RELEASED".equals(row.state)) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                "AI 服务已确认原请求未发送，未扣费", "AI_REQUEST_NOT_SENT",
                Map.of("budgetId",row.budgetId,"settlementState",row.state,"remoteState","FAILED_NOT_SENT"));
        boolean running=remote==BudgetCallState.BUDGET_CALL_STATE_PREPARED || remote==BudgetCallState.BUDGET_CALL_STATE_RUNNING;
        var details=new java.util.LinkedHashMap<String,Object>(Map.of("budgetId",row.budgetId,"requestId",row.requestId,"settlementState",row.state,
                "remoteState",remote==null?"UNKNOWN":remote.name().replace("BUDGET_CALL_STATE_","")));
        if(authority!=null && authority.hasExecutionState()) details.put("executionState",authority.getExecutionState().name().replace("BUDGET_EXECUTION_STATE_",""));
        if(authority!=null && authority.hasExecutionEndedAtEpochMs()) details.put("executionEndedAtEpochMs",authority.getExecutionEndedAtEpochMs());
        boolean terminated="TERMINATED".equals(details.get("executionState")) && details.get("executionEndedAtEpochMs") instanceof Number ended && ended.longValue()>0;
        throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, running?"正在等待原 AI 请求的结果":terminated?"原 AI 请求执行已终止，无完整结果，用量待确认":"原 AI 请求状态暂未确认，保留现场等待查询",
                running?"AI_RESULT_PENDING":"AI_RESULT_UNKNOWN",
                details);
    }
    /** Read-only recovery by the original identity. This method can never prepare or send inference. */
    public ChatCompletionsResponse recoverOriginal(String project,long userId,String requestId) {
        var request=ChatCompletionsRequest.newBuilder().setProjectKey(project).setUserId(userId).setRequestId(requestId).build();
        var row=reservations.findByIdentityHash(identity(request)).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                "原请求尚未注册，未发送", "AI_REQUEST_NOT_PREPARED", Map.of("requestId",requestId,"remoteState","NOT_PREPARED")));
        return recover(row);
    }
    private static String identity(ChatCompletionsRequest request) {
        return AiCallSettlementService.hash((request.getProjectKey()+"\0"+request.getUserId()+"\0"+request.getRequestId()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    /** Admin diagnostics: read/reconcile the original request; this method never executes inference. */
    public Map<String,Object> inspect(String project,long userId,String requestId) {
        var request=ChatCompletionsRequest.newBuilder().setProjectKey(project).setUserId(userId).setRequestId(requestId).build();
        var found=reservations.findByIdentityHash(identity(request));
        if (found.isEmpty()) return Map.of("requestId",requestId,"state","NOT_PREPARED");
        var row=found.get();
        var out=new java.util.LinkedHashMap<String,Object>();out.put("requestId",requestId);out.put("budgetId",row.budgetId);
        GetBudgetCallStatusResponse remote=null;
        if (AiCallSettlementService.pending(row.state)) {
            try { remote=reconcile(row); }
            catch (RuntimeException unavailable) { out.put("queryState","UNAVAILABLE"); }
            row=reservations.findById(row.id).orElseThrow();
        }
        out.put("state",row.state);out.put("billedPoints",row.billedPoints);out.put("billedCost",row.billedCost);
        if (remote!=null) {
            out.put("remoteState",remote.getState().name().replace("BUDGET_CALL_STATE_",""));
            out.put("executionState",remote.hasExecutionState()?remote.getExecutionState().name().replace("BUDGET_EXECUTION_STATE_",""):"UNKNOWN");
            if(remote.hasExecutionEndedAtEpochMs())out.put("executionEndedAtEpochMs",remote.getExecutionEndedAtEpochMs());
            if (remote.hasStreamProgress()) {
                out.put("partialContent",remote.getStreamProgress().getContent());
                if (remote.getStreamProgress().hasTiming()) out.put("timing",timing(remote.getStreamProgress().getTiming()));
            }
        }
        if ("SETTLED".equals(row.state)) {
            var result=result(row);out.put("content",result.getContent());out.put("finishReason",result.getFinishReason());
            out.put("usageAuthoritative",result.hasUsageAuthoritative() ? result.getUsageAuthoritative() : null);
            var usage=new java.util.LinkedHashMap<String,Object>();usage.put("promptTokens",result.getPromptTokens());
            usage.put("completionTokens",result.getCompletionTokens());usage.put("cacheTokens",result.getCacheTokens());
            usage.put("reasoningTokens",result.hasReasoningTokens()?result.getReasoningTokens():null);out.put("usage",usage);
            if (result.hasStreamTiming()) out.put("timing",timing(result.getStreamTiming()));
        }
        return out;
    }
    /** Execution lifecycle must be read from the authority, never inferred from accounting state. */
    public Map<String,Object> inspectExecution(String project,long userId,String requestId) {
        var out=new java.util.LinkedHashMap<>(inspect(project,userId,requestId));
        if(!out.containsKey("budgetId")) return out;
        try {
            var status=remoteStatus(project,userId,requestId,(String)out.get("budgetId"));
            out.put("executionState",status.hasExecutionState()?status.getExecutionState().name().replace("BUDGET_EXECUTION_STATE_",""):"UNKNOWN");
            if(status.hasExecutionEndedAtEpochMs())out.put("executionEndedAtEpochMs",status.getExecutionEndedAtEpochMs());
        } catch(RuntimeException failure) {out.put("executionState","UNKNOWN");}
        return out;
    }
    /** Imports an already charged diagnostic into a run's cost boundary, without touching its wallet. */
    public Map<String,Object> inspectExternalCharge(String project,long userId,String requestId,String budgetId) {
        if(requestId==null || requestId.isBlank() || requestId.length()>128 || budgetId==null || !budgetId.matches("[a-fA-F0-9-]{36}"))
            throw new ApiException(HttpStatus.BAD_REQUEST,"外部诊断请求标识无效");
        var status=remoteStatus(project,userId,requestId,budgetId);
        if(status.getState()!=BudgetCallState.BUDGET_CALL_STATE_SUCCEEDED || !status.getUsageAuthoritative() || !status.hasCharge()
                || !"CNY".equals(status.getCharge().getCurrency()) || status.getCharge().getCreditPoints()<0)
            throw new ApiException(HttpStatus.CONFLICT,"外部诊断尚无权威费用，不可计作已知实耗");
        return Map.of("requestId",requestId,"budgetId",budgetId,"creditPoints",status.getCharge().getCreditPoints(),
                "cost",status.getCharge().getTotalCost(),"currency","CNY","priceVersionId",status.getCharge().getPriceVersionId(),
                "walletDeductionPerformed",false,"usageAuthoritative",true);
    }
    private GetBudgetCallStatusResponse remoteStatus(String project,long userId,String requestId,String budgetId) {
        return stub.withDeadlineAfter(5,TimeUnit.SECONDS).getBudgetCallStatus(GetBudgetCallStatusRequest.newBuilder()
                .setProjectKey(project).setUserId(userId).setRequestId(requestId).setBudgetId(budgetId).setOperation(BudgetOperation.BUDGET_OPERATION_CHAT).build());
    }
    private static Map<String,Object> timing(ChatStreamTiming timing) {
        var out=new java.util.LinkedHashMap<String,Object>();
        out.put("providerStartedAtEpochMs",timing.hasProviderStartedAtEpochMs()?timing.getProviderStartedAtEpochMs():null);
        out.put("firstEventMs",timing.hasFirstEventMs()?timing.getFirstEventMs():null);
        out.put("firstTextMs",timing.hasFirstTextMs()?timing.getFirstTextMs():null);out.put("totalMs",timing.getTotalMs());
        out.put("lastEventAtEpochMs",timing.hasLastEventAtEpochMs()?timing.getLastEventAtEpochMs():null);return out;
    }
    private static boolean transientFailure(RuntimeException failure) {
        return java.util.Set.of(io.grpc.Status.Code.UNAVAILABLE, io.grpc.Status.Code.DEADLINE_EXCEEDED,
                io.grpc.Status.Code.RESOURCE_EXHAUSTED).contains(io.grpc.Status.fromThrowable(failure).getCode());
    }
    private static ApiException notSent() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI 请求尚未发送，可重试", "AI_REQUEST_NOT_SENT", Map.of());
    }
    private GetBudgetCallStatusResponse reconcile(AiCreditReservation row) {
        // A timed-out generation must not also consume the read-only settlement window.
        var status=stub.withDeadlineAfter(5,TimeUnit.SECONDS).getBudgetCallStatus(GetBudgetCallStatusRequest.newBuilder()
            .setBudgetId(row.budgetId).setProjectKey(row.projectKey).setUserId(row.userId).setRequestId(row.requestId)
            .setOperation(BudgetOperation.BUDGET_OPERATION_CHAT).build());
        escrow.reconcile(row.id,status);
        if (debugRecorder != null && status.hasChat()) debugRecorder.response(row.requestId, status.getChat(), status.getUsageAuthoritative() ? Boolean.TRUE : null);
        return status;
    }
    @Scheduled(fixedDelayString="${app.ai.budget-reconcile-delay-ms:30000}")
    public void reconcilePending() {
        // Release historical holds independently of remote availability.
        for (var row:reservations.findByStateOrderByUpdatedAtAsc("HELD",PageRequest.of(0,100))) escrow.releaseLegacyHold(row.id);
        for(var row:reservations.findByStateInOrderByUpdatedAtAsc(java.util.List.of("PENDING","USAGE_UNKNOWN"),PageRequest.of(0,20))) {
            try { reconcile(row); } catch(RuntimeException failure) {
                log.warn("AI usage query deferred callId={} rpcCode={}",row.id,io.grpc.Status.fromThrowable(failure).getCode());
                escrow.markChecked(row.id);
            }
        }
    }
    private ChatCompletionsResponse result(AiCreditReservation row) {
        try{return ChatCompletionsResponse.parseFrom(java.util.Base64.getDecoder().decode(row.resultBase64));}
        catch(Exception ex){throw new IllegalStateException("Committed AI result is unreadable",ex);}
    }
}
