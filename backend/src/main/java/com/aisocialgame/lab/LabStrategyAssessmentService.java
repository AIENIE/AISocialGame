package com.aisocialgame.lab;

import com.aisocialgame.exception.ApiException;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;
import static com.aisocialgame.engine.v2.RuleSupport.*;

/** Human review findings are mutable experiment metadata, never edits to an immutable strategy version. */
@Service
public class LabStrategyAssessmentService {
    public record Request(@NotNull Boolean ruleError,@NotNull Boolean evidenceLeak,@NotNull Boolean severeContradiction,
                          @NotNull @Size(max=4000) String notes) {}
    private final LabExperimentRepository experiments;
    private final LabStrategyVersionRepository versions;
    public LabStrategyAssessmentService(LabExperimentRepository experiments,LabStrategyVersionRepository versions) {
        this.experiments=experiments;this.versions=versions;
    }

    @Transactional
    public Map<String,Object> assess(String experimentId,String versionId,Request request,String operator) {
        var experiment=experiments.lock(experimentId).orElseThrow(LabService::missing);
        var version=versions.findById(versionId).orElseThrow(LabService::missing);
        require(experimentId.equals(version.experimentId),"策略不属于此实验");
        require(request.ruleError()!=null && request.evidenceLeak()!=null && request.severeContradiction()!=null
                && request.notes()!=null && request.notes().length()<=4000,"请明确填写三项评审结论，备注不得超过 4000 字符");
        var assessment=new LinkedHashMap<String,Object>();assessment.put("strategyVersionId",versionId);
        assessment.put("ruleError",request.ruleError());assessment.put("evidenceLeak",request.evidenceLeak());
        assessment.put("severeContradiction",request.severeContradiction());assessment.put("notes",request.notes().strip());
        assessment.put("reviewedBy",operator);assessment.put("reviewedAt",Instant.now().toString());
        var all=new LinkedHashMap<>(map(experiment.configuration.get("strategyAssessments")));all.put(versionId,assessment);
        experiment.configuration.put("strategyAssessments",all);experiments.saveAndFlush(experiment);
        return LabStrategyService.copy(assessment);
    }

    /** Share the experiment lock with assessment writes so publication never races a saved finding. */
    @Transactional
    public void requirePublishable(LabStrategyVersion version) {
        var experiment=experiments.lock(version.experimentId).orElseThrow(LabService::missing);
        var assessment=map(map(experiment.configuration.get("strategyAssessments")).get(version.id));
        if(List.of("ruleError","evidenceLeak","severeContradiction").stream().anyMatch(key->Boolean.TRUE.equals(assessment.get(key))))
            throw new ApiException(HttpStatus.CONFLICT,"该策略存在已确认的规则错误、不可见证据依赖或严重矛盾，请先修正并重新评审", "STRATEGY_ASSESSMENT_BLOCKED",LabStrategyService.copy(assessment));
    }
}
