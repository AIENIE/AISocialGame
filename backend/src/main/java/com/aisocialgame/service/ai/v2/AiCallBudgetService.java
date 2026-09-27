package com.aisocialgame.service.ai.v2;

import com.aisocialgame.exception.ApiException;
import com.aisocialgame.model.AiCallBudget;
import com.aisocialgame.repository.AiCallBudgetRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.Objects;

/** A persistent, shared ceiling for an explicitly configured validation run. Zero disables it. */
@Service
public class AiCallBudgetService {
    private final AiCallBudgetRepository repository;
    private final TransactionTemplate transaction;
    private final int limit;
    private final String runId;
    private boolean initialized;
    public AiCallBudgetService(AiCallBudgetRepository repository, org.springframework.transaction.PlatformTransactionManager manager,
                              @Value("${app.ai.validation-call-limit:0}") int limit,
                              @Value("${app.ai.validation-run-id:game-realism-v2}") String runId) {
        this.repository = repository; this.transaction = new TransactionTemplate(manager);
        this.transaction.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.limit = Math.max(0, limit); this.runId = runId;
    }
    public synchronized void consume() {
        if (limit == 0) return;
        if (!initialized) {
            transaction.executeWithoutResult(status -> {
                if (!repository.existsById(runId)) repository.saveAndFlush(new AiCallBudget(runId));
            });
            initialized = true;
        }
        boolean allowed = Boolean.TRUE.equals(transaction.execute(status -> {
            AiCallBudget budget = repository.findByIdForUpdate(runId).orElseThrow();
            if (budget.getConsumed() >= limit) return false;
            budget.setConsumed(budget.getConsumed() + 1); repository.save(budget); return true;
        }));
        if (!allowed) throw new com.aisocialgame.service.safety.AiCallBlockedException(com.aisocialgame.service.safety.AiCallBlockedException.Reason.CALL_BUDGET_EXHAUSTED);
    }
    public int consumed() { return limit == 0 ? 0 : repository.findById(runId).map(AiCallBudget::getConsumed).orElse(0); }
    public int limit() { return limit; }
    public String runId() { return runId; }
}
