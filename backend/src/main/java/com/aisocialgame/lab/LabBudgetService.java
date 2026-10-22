package com.aisocialgame.lab;

import com.aisocialgame.exception.ApiException;
import com.aisocialgame.model.credit.AiCreditReservation;
import com.aisocialgame.repository.credit.AiCreditReservationRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.Map;
import java.util.Set;

@Service
public class LabBudgetService {
    public static final String EXHAUSTED = "LAB_BUDGET_EXHAUSTED";
    private static final Set<String> ACTIVE = Set.of("RUNNING", "PAUSING", "STOPPING");
    private final LabExperimentRepository experiments;
    private final AiCreditReservationRepository reservations;
    public LabBudgetService(LabExperimentRepository experiments, AiCreditReservationRepository reservations) {
        this.experiments = experiments; this.reservations = reservations;
    }
    /** Admission precedes PrepareCallBudget; in-flight quotes do not consume the experiment's spending budget. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void checkCall() {
        var identity = LabCallContext.current();
        if (identity == null) return;
        var experiment = experiments.lock(identity.experimentId()).orElseThrow();
        if (paused(experiment)) throw exhausted(experiment);
        if (!ACTIVE.contains(experiment.status))
            throw new ApiException(HttpStatus.CONFLICT, "实验已暂停", "LAB_EXPERIMENT_PAUSED", Map.of());
        requireRemaining(experiment);
    }
    public long spentPoints(String id) { return reservations.labTotals(id).getSpentPoints(); }
    public void requireRemaining(LabExperiment experiment) {
        if (spentPoints(experiment.id) >= experiment.budgetPoints) throw exhausted(experiment);
    }
    public boolean isPaused(String id) {
        return experiments.findById(id).map(experiment -> paused(experiment)
                || ACTIVE.contains(experiment.status) && spentPoints(id) >= experiment.budgetPoints).orElse(false);
    }
    public static boolean paused(LabExperiment experiment) { return Boolean.TRUE.equals(experiment.configuration.get("budgetPaused")); }
    public boolean pauseIfReached(LabExperiment experiment) {
        if (!ACTIVE.contains(experiment.status) || spentPoints(experiment.id) < experiment.budgetPoints) return false;
        markPaused(experiment);
        return true;
    }
    public static void markPaused(LabExperiment experiment) {
        experiment.status = "PAUSED"; experiment.reason = EXHAUSTED;
        experiment.configuration.put("budgetPaused", true);
    }
    private ApiException exhausted(LabExperiment experiment) {
        return new ApiException(HttpStatus.CONFLICT, "已达到实验预算，调整预算后可继续", EXHAUSTED,
                Map.of("budgetPoints", experiment.budgetPoints, "spentPoints", spentPoints(experiment.id)));
    }
    public void bind(AiCreditReservation row) {
        var identity = LabCallContext.current();
        if (identity == null) return;
        row.labExperimentId = identity.experimentId(); row.labMatchId = identity.matchId(); row.labPurpose = identity.purpose();
    }
    public Map<String,Object> totals(String experimentId) {
        var totals = reservations.labTotals(experimentId);
        return Map.of("spentPoints", totals.getSpentPoints(), "heldPoints", totals.getHeldPoints(),
                "pendingCalls", totals.getPendingCalls(), "pendingPoints", totals.getHeldPoints(),
                "totalCalls", totals.getTotalCalls(), "actualCost", totals.getActualCost());
    }
}
