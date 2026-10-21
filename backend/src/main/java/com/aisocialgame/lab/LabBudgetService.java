package com.aisocialgame.lab;

import com.aisocialgame.exception.ApiException;
import com.aisocialgame.model.credit.AiCreditReservation;
import com.aisocialgame.repository.credit.AiCreditReservationRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.util.Map;

@Service
public class LabBudgetService {
    private final LabExperimentRepository experiments;
    private final AiCreditReservationRepository reservations;
    public LabBudgetService(LabExperimentRepository experiments, AiCreditReservationRepository reservations) {
        this.experiments = experiments; this.reservations = reservations;
    }
    public void checkReservation(long required) {
        var identity = LabCallContext.current();
        if (identity == null) return;
        var experiment = experiments.lock(identity.experimentId()).orElseThrow();
        if (!java.util.Set.of("RUNNING", "PAUSING", "STOPPING").contains(experiment.status))
            throw new ApiException(HttpStatus.CONFLICT, "实验已暂停", "LAB_EXPERIMENT_PAUSED", Map.of());
        var totals = reservations.labTotals(experiment.id);
        if (Math.addExact(Math.addExact(totals.getSpentPoints(), totals.getHeldPoints()), required) > experiment.budgetPoints)
            throw new ApiException(HttpStatus.CONFLICT, "实验剩余额度不足以预留本次调用", "LAB_BUDGET_EXHAUSTED", Map.of());
    }
    public void bind(AiCreditReservation row) {
        var identity = LabCallContext.current();
        if (identity == null) return;
        row.labExperimentId = identity.experimentId(); row.labMatchId = identity.matchId(); row.labPurpose = identity.purpose();
    }
    public Map<String,Object> totals(String experimentId) {
        var totals = reservations.labTotals(experimentId);
        return Map.of("spentPoints", totals.getSpentPoints(), "heldPoints", totals.getHeldPoints(),
                "pendingCalls", totals.getPendingCalls(), "totalCalls", totals.getTotalCalls(), "actualCost", totals.getActualCost());
    }
}
