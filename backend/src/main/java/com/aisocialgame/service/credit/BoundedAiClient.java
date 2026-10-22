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
    private final AiCreditEscrowService escrow;
    private final AiCreditReservationRepository reservations;
    private final AiCallBudgetService callBudget;
    private final boolean enabled;
    private final int maxOutput;
    public BoundedAiClient(AiGatewayServiceGrpc.AiGatewayServiceBlockingStub stub,AiCreditEscrowService escrow,
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
        request = originalDebugFlag(request);
        escrow.beginRequest(request.getRequestId());
        try { return execute(request, deadlineSeconds, outputLimit, beforeSend); }
        finally { escrow.endRequest(request.getRequestId()); }
    }
    // Old reservations are bound to the exact request bytes, including absence of diagnostic flags.
    private ChatCompletionsRequest originalDebugFlag(ChatCompletionsRequest request) {
        if (!request.hasIncludeDebug()) return request;
        var original = reservations.findByIdentityHash(identity(request)).orElse(null);
        if (original == null || original.requestHash.equals(AiCreditEscrowService.hash(request.toByteArray()))) return request;
        var legacy = request.toBuilder().clearIncludeDebug().build();
        return original.requestHash.equals(AiCreditEscrowService.hash(legacy.toByteArray())) ? legacy : request;
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
            if (!previous.requestHash.equals(AiCreditEscrowService.hash(request.toByteArray())))
                throw new ApiException(HttpStatus.CONFLICT,"AI 请求标识与原内容不一致","BUDGET_REQUEST_CONFLICT",Map.of());
            return recover(previous);
        }
        if (labBudgets != null) labBudgets.checkCall();
        long remainingSeconds=Math.min(120,Math.max(1,deadlineSeconds));
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
        var claim=escrow.reserve(request,budget);
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
                stub.withDeadlineAfter(remaining,TimeUnit.NANOSECONDS)
                    .chatCompletions(request.toBuilder().setBudgetCredential(budget.getBudgetCredential()).build());
            } catch(RuntimeException failure) {
                // Local admission/deadline failures have not attempted the execution RPC.
                // Keep the hold until the prepared server budget expires, but preserve the actual cause.
                if (!executionStarted) throw failure;
                // Querying is safe; neither this path nor the worker retries inference.
                return recover(claim.reservation());
            }
        }
        return recover(claim.reservation());
    }
    private ChatCompletionsResponse recover(AiCreditReservation row) {
        BudgetCallState remote=null;
        if ("HELD".equals(row.state)) {
            try { remote=reconcile(row).getState(); }
            catch (RuntimeException unavailable) { log.info("AI result query deferred requestId={} errorType={}",row.requestId,unavailable.getClass().getSimpleName()); }
            row=reservations.findById(row.id).orElseThrow();
        }
        if ("SETTLED".equals(row.state)) return result(row);
        if ("RELEASED".equals(row.state)) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                "AI 服务已确认原请求未发送，冻结积分已释放", "AI_REQUEST_NOT_SENT",
                Map.of("budgetId",row.budgetId,"settlementState",row.state,"remoteState","FAILED_NOT_SENT"));
        boolean running=remote==BudgetCallState.BUDGET_CALL_STATE_PREPARED || remote==BudgetCallState.BUDGET_CALL_STATE_RUNNING;
        throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, running?"正在等待原 AI 请求的结果":"原 AI 请求状态暂未确认，保留现场等待查询",
                running?"AI_RESULT_PENDING":"AI_RESULT_UNKNOWN",
                Map.of("budgetId",row.budgetId,"settlementState",row.state,"remoteState",remote==null?"UNKNOWN":remote.name().replace("BUDGET_CALL_STATE_","")));
    }
    private static String identity(ChatCompletionsRequest request) {
        return AiCreditEscrowService.hash((request.getProjectKey()+"\0"+request.getUserId()+"\0"+request.getRequestId()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
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
        // Continue settling old holds even while new inference is disabled.
        for(var row:reservations.findByStateOrderByUpdatedAtAsc("HELD",PageRequest.of(0,5))) {
            try { reconcile(row); } catch(RuntimeException failure) {
                log.warn("AI budget reconciliation pending reservationId={} rpcCode={}",row.id,io.grpc.Status.fromThrowable(failure).getCode());
                escrow.markChecked(row.id);
            }
        }
    }
    private ChatCompletionsResponse result(AiCreditReservation row) {
        try{return ChatCompletionsResponse.parseFrom(java.util.Base64.getDecoder().decode(row.resultBase64));}
        catch(Exception ex){throw new IllegalStateException("Committed AI result is unreadable",ex);}
    }
}
