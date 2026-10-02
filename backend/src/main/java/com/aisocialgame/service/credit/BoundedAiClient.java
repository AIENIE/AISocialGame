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
            @Value("${app.ai.budget-max-output-tokens:1024}") int maxOutput) {
        this.stub=stub; this.escrow=escrow; this.reservations=reservations; this.callBudget=callBudget;
        this.enabled=enabled; this.maxOutput=maxOutput;
    }
    public void requireReady() {
        if(!enabled || maxOutput<=0) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"AI 预算能力暂不可用，请稍后再试","BUDGET_UNAVAILABLE",Map.of());
    }
    public ChatCompletionsResponse chat(ChatCompletionsRequest request,int deadlineSeconds) {
        requireReady();
        long remainingSeconds=Math.min(45,Math.max(1,deadlineSeconds));
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(remainingSeconds);
        var budget=stub.withDeadlineAfter(Math.min(5,remainingSeconds),TimeUnit.SECONDS).prepareCallBudget(PrepareCallBudgetRequest.newBuilder()
            .setChat(request).setMaxOutputTokens(maxOutput).setMaxProviderAttempts(1).build());
        var claim=escrow.reserve(request,budget);
        if(claim.acquired()) {
            try {
                long remaining=deadline-System.nanoTime();
                if(remaining<=0) throw io.grpc.Status.DEADLINE_EXCEEDED.asRuntimeException();
                callBudget.consume();
                stub.withDeadlineAfter(remaining,TimeUnit.NANOSECONDS)
                    .chatCompletions(request.toBuilder().setBudgetCredential(budget.getBudgetCredential()).build());
            } catch(RuntimeException failure) {
                // Querying is safe; neither this path nor the worker retries inference.
                try { reconcile(claim.reservation()); } catch(RuntimeException queryFailure) { failure.addSuppressed(queryFailure); }
                var committed=reservations.findById(claim.reservation().id).orElseThrow();
                if("SETTLED".equals(committed.state)) return result(committed);
                throw failure;
            }
        }
        reconcile(claim.reservation());
        var committed=reservations.findById(claim.reservation().id).orElseThrow();
        if("SETTLED".equals(committed.state)) return result(committed);
        throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"AI 调用结果正在对账，已预留额度不会重复扣取","BUDGET_RECONCILIATION_REQUIRED",Map.of());
    }
    private void reconcile(AiCreditReservation row) {
        var status=stub.withDeadlineAfter(5,TimeUnit.SECONDS).getBudgetCallStatus(GetBudgetCallStatusRequest.newBuilder()
            .setBudgetId(row.budgetId).setProjectKey(row.projectKey).setUserId(row.userId).setRequestId(row.requestId)
            .setOperation(BudgetOperation.BUDGET_OPERATION_CHAT).build());
        escrow.reconcile(row.id,status);
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
