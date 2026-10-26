package com.aisocialgame.lab;

import com.aisocialgame.repository.*;
import com.aisocialgame.service.ai.config.AiModelConfigService;
import com.aisocialgame.service.v2.V2GameService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import java.util.*;
import static com.aisocialgame.engine.v2.RuleSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LabScenarioControlTest {
    final LabExperiment experiment=new LabExperiment();
    final LabExperimentRepository experiments=mock(LabExperimentRepository.class);
    final LabBudgetService budgets=mock(LabBudgetService.class);
    LabService service;
    @BeforeEach void setup() {
        experiment.name="scenario";experiment.status="PAUSED";experiment.budgetPoints=3000;
        experiment.configuration.put("gameId","werewolf");experiment.configuration.put("activeScenario","op");
        var current=new LinkedHashMap<String,Object>();current.put("deadlineEpochMs",System.currentTimeMillis()+30_000);current.put("remainingMillis",50_000);current.put("checkpoint",Map.of("originalRequestId","keep-original"));
        var run=new LinkedHashMap<String,Object>();run.put("status","RUNNING");run.put("current",current);
        experiment.configuration.put("scenarioRuns",new LinkedHashMap<>(Map.of("op",run)));
        when(experiments.lock(experiment.id)).thenReturn(Optional.of(experiment));when(experiments.findById(experiment.id)).thenReturn(Optional.of(experiment));
        when(budgets.spentPoints(experiment.id)).thenReturn(1000L);
        service=new LabService(experiments,mock(LabAgentRepository.class),mock(LabExperienceRepository.class),mock(LabMatchRepository.class),
                mock(LabEnrollmentRepository.class),mock(LabEvaluationRepository.class),mock(PersonaRepository.class),mock(AiModelConfigService.class),budgets,
                new ObjectMapper().findAndRegisterModules(),mock(RoomRepository.class),mock(V2GameService.class));
    }
    Map<String,Object> run() {return map(map(experiment.configuration.get("scenarioRuns")).get("op"));}
    @Test void loweringToActualSpendPausesScenarioAndRaisingDoesNotResumeOrLoseTime() {
        service.updateBudget(experiment.id,1000);assertEquals("PAUSED",run().get("status"));assertTrue(LabBudgetService.paused(experiment));
        long remaining=((Number)map(run().get("current")).get("remainingMillis")).longValue();assertTrue(remaining>0 && remaining<=30_000);
        service.updateBudget(experiment.id,2000);assertEquals("PAUSED",run().get("status"));assertEquals(remaining,((Number)map(run().get("current")).get("remainingMillis")).longValue());
        service.control(experiment.id,"resume");assertEquals("RUNNING",run().get("status"));assertEquals("PAUSED",experiment.status);assertFalse(LabBudgetService.paused(experiment));
        assertEquals("keep-original",map(map(run().get("current")).get("checkpoint")).get("originalRequestId"));
    }
    @Test void exhaustedScenarioOpportunityDoesNotGainAnExtraSecondOnResume() {
        var runs=map(experiment.configuration.get("scenarioRuns"));var run=map(runs.get("op"));run.put("status","PAUSED");
        var current=map(run.get("current"));current.put("remainingMillis",0);run.put("current",current);runs.put("op",run);experiment.configuration.put("scenarioRuns",runs);
        service.control(experiment.id,"resume");assertTrue(((Number)map(run().get("current")).get("deadlineEpochMs")).longValue()<=System.currentTimeMillis());
    }
}
