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
        requireReady();
        request = originalDebugFlag(request);
        escrow.beginRequest(request.getRequestId());
        try { return execute(request, deadlineSeconds, beforeSend); }
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
    private ChatCompletionsResponse execute(ChatCompletionsRequest request, int deadlineSeconds, Runnable beforeSend) {
        var previous=reservations.findByIdentityHash(identity(request)).orElse(null);
        if (previous!=null) {
            if (!previous.requestHash.equals(AiCreditEscrowService.hash(request.toByteArray())))
                throw new ApiException(HttpStatus.CONFLICT,"AI 请求标识与原内容不一致","BUDGET_REQUEST_CONFLICT",Map.of());
            if ("HELD".equals(previous.state)) {
                try { reconcile(previous); } catch (RuntimeException failure) { throw pending(previous); }
                previous=reservations.findById(previous.id).orElseThrow();
            }
            if ("SETTLED".equals(previous.state)) return result(previous);
            if ("RELEASED".equals(previous.state)) throw notSent();
            throw pending(previous);
        }
        if (labBudgets != null) labBudgets.checkCall();
        long remainingSeconds=Math.min(89,Math.max(1,deadlineSeconds));
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(remainingSeconds);
        PrepareCallBudgetResponse budget;
        try {
            budget=stub.withDeadlineAfter(Math.min(5,remainingSeconds),TimeUnit.SECONDS).prepareCallBudget(PrepareCallBudgetRequest.newBuilder()
                .setChat(request).setMaxOutputTokens(maxOutput).setMaxProviderAttempts(1).setBillingCurrency("CNY").build());
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
                try { reconcile(claim.reservation()); } catch(RuntimeException queryFailure) { failure.addSuppressed(queryFailure); }
                var committed=reservations.findById(claim.reservation().id).orElseThrow();
                if("SETTLED".equals(committed.state)) return result(committed);
                if ("RELEASED".equals(committed.state) && transientFailure(failure)) throw notSent();
                throw pending(claim.reservation());
            }
        }
        try { reconcile(claim.reservation()); } catch (RuntimeException failure) { throw pending(claim.reservation()); }
        var committed=reservations.findById(claim.reservation().id).orElseThrow();
        if("SETTLED".equals(committed.state)) return result(committed);
        if ("RELEASED".equals(committed.state)) throw notSent();
        throw pending(claim.reservation());
    }
    private static ApiException pending(AiCreditReservation row) {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI 调用结果正在对账", "BUDGET_RECONCILIATION_REQUIRED",
                Map.of("budgetId", row.budgetId, "settlementState", row.state));
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
    private void reconcile(AiCreditReservation row) {
        // A timed-out generation must not also consume the read-only settlement window.
        var status=stub.withDeadlineAfter(5,TimeUnit.SECONDS).getBudgetCallStatus(GetBudgetCallStatusRequest.newBuilder()
            .setBudgetId(row.budgetId).setProjectKey(row.projectKey).setUserId(row.userId).setRequestId(row.requestId)
            .setOperation(BudgetOperation.BUDGET_OPERATION_CHAT).build());
        escrow.reconcile(row.id,status);
        if (debugRecorder != null && status.hasChat()) debugRecorder.response(row.requestId, status.getChat(), status.getUsageAuthoritative() ? Boolean.TRUE : null);
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
