package com.aisocialgame.lab;

import com.aisocialgame.web.CurrentAdmin;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/admin/lab")
public class AdminLabStrategyController {
    private final LabStrategyService strategies;
    private final StrategyPublicationService publications;
    private final LabExperimentRepository experiments;
    private final LabStrategyAssessmentService assessments;
    public AdminLabStrategyController(LabStrategyService strategies,StrategyPublicationService publications,LabExperimentRepository experiments,LabStrategyAssessmentService assessments) {
        this.strategies=strategies;this.publications=publications;this.experiments=experiments;this.assessments=assessments;
    }
    @GetMapping("/experiments/{id}/strategies")
    public Map<String,Object> list(@CurrentAdmin String operator,@PathVariable String id) {
        var experiment=experiments.findById(id).orElseThrow(LabService::missing);
        return Map.of("items",strategies.list(id),"strategyHeadId",experiment.configuration.getOrDefault("strategyHeadId",""),"bindings",publications.bindings(),
                "assessments",experiment.configuration.getOrDefault("strategyAssessments",Map.of()));
    }
    @PostMapping("/experiments/{id}/strategies/{versionId}/assessment")
    public Map<String,Object> assess(@CurrentAdmin String operator,@PathVariable String id,@PathVariable String versionId,
                                     @Valid @RequestBody LabStrategyAssessmentService.Request request) {
        return assessments.assess(id,versionId,request,operator);
    }
    @PostMapping("/strategy-publications")
    public Map<String,Object> publish(@CurrentAdmin String operator,@Valid @RequestBody StrategyPublicationService.Request request) {return publications.apply(request,operator);}
    @GetMapping("/strategy-publications/{operationId}")
    public Map<String,Object> inspect(@CurrentAdmin String operator,@PathVariable String operationId) {return publications.inspect(operationId);}
}
