package com.aisocialgame.lab;

import com.aisocialgame.engine.v2.werewolf.WerewolfRuleSet;
import com.aisocialgame.exception.ApiException;
import com.aisocialgame.service.ai.v2.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.*;
import java.util.function.*;
import static com.aisocialgame.engine.v2.RuleSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LabScenarioServiceTest {
    final LabExperimentRepository experiments=mock(LabExperimentRepository.class);
    final LabMatchRepository matches=mock(LabMatchRepository.class);
    final LabStrategyService strategies=mock(LabStrategyService.class);
    final LabBudgetService budgets=mock(LabBudgetService.class);
    final AiTurnGenerator generator=mock(AiTurnGenerator.class);
    final WerewolfRuleSet rules=mock(WerewolfRuleSet.class);
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    final LabExperiment experiment=new LabExperiment();
    final Map<String,Object> selection=Map.of("modelKey","fixture","thinkingMode","ENABLED","thinkingEffort","LOW");
    final Map<String,Object> ruleConfig=Map.of("template","standard","playerCount",9,"witchRule","first_night","winCondition","side","hasLastWords","first_night");
    LabScenarioService service;
    @BeforeEach void setup() {
        service=new LabScenarioService(experiments,matches,strategies,budgets,generator,rules,json,mock(PlatformTransactionManager.class));
        experiment.status="PAUSED";experiment.configuration.put("gameId","werewolf");experiment.budgetPoints=3000;
        when(experiments.lock(experiment.id)).thenReturn(Optional.of(experiment));when(experiments.findById(experiment.id)).thenReturn(Optional.of(experiment));
        when(matches.findByExperimentIdAndStatusInOrderBySequenceNumberAsc(anyString(),anyList())).thenReturn(List.of());
        when(budgets.totals(experiment.id)).thenReturn(Map.of("spentPoints",0));
        var card=Map.<String,Object>of("id","facts","role","GENERAL","phases",List.of(),"condition","有公开指控","advice","区分已证实事实和声明","exceptions","无公开证据");
        when(strategies.strategySnapshot("candidate")).thenReturn(Map.of("id","candidate","experimentId",experiment.id,"contentHash","hash", "content",Map.of("cards",List.of(card)),
                "compatibility",Map.of("gameId","werewolf","rules",ruleConfig,"modelSelection",selection)));
        var manifest=new ArrayList<Map<String,Object>>();
        for(int i=0;i<20;i++) {
            var observation=new VisibleObservation("werewolf","fixture-"+i,"DAY_DISCUSS",1,"actor","SPEAK",Map.of("role","VILLAGER"),List.of(),ruleConfig,
                    List.of(Map.of("eventId","visible")),Map.of(),List.of(),Map.of("id","ai"+i%4),Map.of("lab",Map.of("experimentId",experiment.id)),List.of());
            manifest.add(Map.of("caseId","case-"+i,"personaId","ai"+i%4,"role","VILLAGER","observation",json.convertValue(observation,Map.class),"modelSelection",selection,"seed",(long)i));
        }
        experiment.configuration.put("scenarioManifest",manifest);
        when(generator.generate(any(),any(),anyString(),anyLong(),any(),anyMap(),any())).thenReturn(new AiTurnDecision(action("SPEAK","判断保持开放",null),"判断保持开放",Map.of(),List.of("visible"),Map.of(),false,Map.of("calls",1)));
    }
    @Test void fortyCallsUseFrozenAlternativesAndOriginalOperationNeverRunsAgain() {
        var request=new LabScenarioService.Request("operation-1","candidate");service.create(experiment.id,request);
        for(int i=0;i<40;i++) assertTrue(service.process(experiment.id));
        var run=service.inspect(experiment.id,request.operationId());assertEquals("COMPLETED",run.get("status"));assertEquals(40,maps(run.get("results")).size());
        assertEquals("PAUSED",experiment.status);assertFalse(experiment.configuration.containsKey("activeScenario"));
        assertEquals(run,service.create(experiment.id,request));assertFalse(service.process(experiment.id));
        var observed=org.mockito.ArgumentCaptor.forClass(VisibleObservation.class);
        verify(generator,times(40)).generate(any(),observed.capture(),anyString(),longThat(value->value>0 && value<=50_000),any(),anyMap(),any());
        assertEquals(20,observed.getAllValues().stream().filter(value->value.memory().containsKey("strategy")).count());
        assertEquals(20,observed.getAllValues().stream().filter(value->!value.memory().containsKey("strategy")).count());
        assertFalse(experiment.configuration.containsKey("strategyHeadId"));verify(strategies,never()).createVersion(any(),any(),anyString(),anyMap(),anyMap());
        assertThrows(ApiException.class,()->service.create(experiment.id,new LabScenarioService.Request("operation-1","different")));
    }
    @Test void confirmedUnsentRetryKeepsBusinessJobAndCheckpointAcrossWorkerRecreation() {
        var checkpoint=new LinkedHashMap<String,Object>();checkpoint.put("modelSelection",selection);checkpoint.put("transportAttempt",1);checkpoint.put("lastCallCode","AI_REQUEST_NOT_SENT");
        when(generator.generate(any(),any(),anyString(),anyLong(),any(),anyMap(),any()))
                .thenThrow(new AiTurnGenerator.GenerationDeferred(checkpoint,"AI_REQUEST_NOT_SENT"))
                .thenReturn(new AiTurnDecision(action("SPEAK","等待证据",null),"等待证据",Map.of(),List.of(),Map.of(),false,Map.of()));
        service.create(experiment.id,new LabScenarioService.Request("operation-2","candidate"));assertTrue(service.process(experiment.id));
        var restored=new LabScenarioService(experiments,matches,strategies,budgets,generator,rules,json,mock(PlatformTransactionManager.class));
        assertTrue(restored.process(experiment.id));var run=restored.inspect(experiment.id,"operation-2");assertEquals(1,run.get("nextIndex"));
        var ids=org.mockito.ArgumentCaptor.forClass(String.class);var checkpoints=org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(generator,times(2)).generate(any(),any(),ids.capture(),anyLong(),any(),checkpoints.capture(),any());
        assertEquals(ids.getAllValues().getFirst(),ids.getAllValues().getLast());assertEquals(1,checkpoints.getAllValues().getLast().get("transportAttempt"));
    }
    @Test void uncertainOriginalPausesWithoutAdvancingOrRegenerating() {
        when(generator.generate(any(),any(),anyString(),anyLong(),any(),anyMap(),any())).thenThrow(new AiTurnGenerator.GenerationDeferred(
                Map.of("lastCallCode","AI_USAGE_UNKNOWN","lastCallState",Map.of("requestId","original")),"LAB_CALL_UNCERTAIN"));
        service.create(experiment.id,new LabScenarioService.Request("operation-3","candidate"));service.process(experiment.id);
        var run=service.inspect(experiment.id,"operation-3");assertEquals("PAUSED",run.get("status"));assertEquals(0,run.get("nextIndex"));
        assertEquals("original",map(map(map(run.get("current")).get("checkpoint")).get("lastCallState")).get("requestId"));
        assertFalse(service.process(experiment.id));verify(generator,times(1)).generate(any(),any(),anyString(),anyLong(),any(),anyMap(),any());
    }
    @Test void failuresRemainInDenominatorAndBudgetStopsBeforeNextCall() {
        when(generator.generate(any(),any(),anyString(),anyLong(),any(),anyMap(),any())).thenThrow(new AiTurnGenerator.GenerationFailure("TIMEOUT",Map.of("calls",1)));
        service.create(experiment.id,new LabScenarioService.Request("operation-4","candidate"));service.process(experiment.id);
        doThrow(new ApiException(HttpStatus.CONFLICT,"实际费用已到线","LAB_BUDGET_EXHAUSTED",Map.of())).when(budgets).requireRemaining(experiment);
        assertFalse(service.process(experiment.id));var run=service.inspect(experiment.id,"operation-4");
        assertEquals("PAUSED",run.get("status"));assertEquals(1,run.get("nextIndex"));assertEquals("FAILED",maps(run.get("results")).getFirst().get("status"));assertEquals(40,run.get("totalCalls"));
        verify(generator,times(1)).generate(any(),any(),anyString(),anyLong(),any(),anyMap(),any());
    }

    @Test void lateOriginalResultIsSavedAsTimeoutRatherThanSuccessAfterRestart() {
        when(generator.generate(any(),any(),anyString(),anyLong(),any(),anyMap(),any()))
                .thenThrow(new AiTurnGenerator.GenerationDeferred(Map.of("lastCallCode","AI_RESULT_PENDING","originalRequestId","original","calls",1),"AI_RESULT_PENDING"))
                .thenReturn(new AiTurnDecision(action("SPEAK","迟到但保存的结果",null),"迟到但保存的结果",Map.of(),List.of(),Map.of(),false,Map.of("calls",1,"usageComplete",true)));
        service.create(experiment.id,new LabScenarioService.Request("operation-late","candidate"));service.process(experiment.id);
        var runs=map(experiment.configuration.get("scenarioRuns"));var run=map(runs.get("operation-late"));var current=map(run.get("current"));
        current.put("deadlineEpochMs",System.currentTimeMillis()-60_000);run.put("current",current);runs.put("operation-late",run);experiment.configuration.put("scenarioRuns",runs);
        var restored=new LabScenarioService(experiments,matches,strategies,budgets,generator,rules,json,mock(PlatformTransactionManager.class));
        restored.process(experiment.id);var result=maps(restored.inspect(experiment.id,"operation-late").get("results")).getFirst();
        assertEquals("FAILED",result.get("status"));assertEquals("TIMEOUT",result.get("reason"));assertEquals(true,result.get("originalResultRecovered"));
        assertEquals("迟到但保存的结果",result.get("speech"));assertEquals("original",map(result.get("checkpoint")).get("originalRequestId"));
        verify(generator).generate(any(),any(),anyString(),eq(0L),any(),anyMap(),any());
    }

    @Test void ambiguousFailureKeepsOriginalAndPausesInsteadOfAdvancing() {
        when(generator.generate(any(),any(),anyString(),anyLong(),any(),anyMap(),any())).thenAnswer(call->{
            Consumer<Map<String,Object>> save=call.getArgument(6);save.accept(Map.of("originalRequestId","original","calls",1));
            throw new AiTurnGenerator.GenerationFailure("TIMEOUT",Map.of("calls",1,"usageComplete",false));
        });
        service.create(experiment.id,new LabScenarioService.Request("operation-unknown","candidate"));service.process(experiment.id);
        var run=service.inspect(experiment.id,"operation-unknown");assertEquals("PAUSED",run.get("status"));assertEquals("LAB_CALL_UNCERTAIN",run.get("reason"));
        assertEquals(0,run.get("nextIndex"));assertTrue(maps(run.get("results")).isEmpty());assertFalse(service.process(experiment.id));
    }

    @Test void stopDuringRpcCannotBeUndoneByLateCompletionOrDeferredUpdate() {
        for(boolean deferred:List.of(false,true)) {
            experiment.status="PAUSED";experiment.configuration.remove("activeScenario");String operation="operation-stop-"+deferred;
            service.create(experiment.id,new LabScenarioService.Request(operation,"candidate"));
            doAnswer(call->{
                var runs=map(experiment.configuration.get("scenarioRuns"));var run=map(runs.get(operation));run.put("totalCalls",1);run.put("status","STOPPED");run.put("reason","ADMIN_STOPPED");
                runs.put(operation,run);experiment.configuration.put("scenarioRuns",runs);experiment.status="STOPPED";experiment.configuration.put("activeScenario","replacement");
                if(deferred) throw new AiTurnGenerator.GenerationDeferred(Map.of("originalRequestId","original"),"LAB_CALL_UNCERTAIN");
                return new AiTurnDecision(action("SPEAK","保存结果",null),"保存结果",Map.of(),List.of(),Map.of(),false,Map.of());
            }).when(generator).generate(any(),any(),anyString(),anyLong(),any(),anyMap(),any());
            assertTrue(service.process(experiment.id));var run=service.inspect(experiment.id,operation);
            assertEquals("STOPPED",run.get("status"));assertEquals("ADMIN_STOPPED",run.get("reason"));assertEquals("STOPPED",experiment.status);
            assertEquals("replacement",experiment.configuration.get("activeScenario"));
        }
    }

    @Test void administrativePauseSurvivesAnInflightSuccessfulResult() {
        service.create(experiment.id,new LabScenarioService.Request("operation-pause","candidate"));
        doAnswer(call->{
            var runs=map(experiment.configuration.get("scenarioRuns"));var run=map(runs.get("operation-pause"));run.put("status","PAUSED");run.put("reason","ADMIN_PAUSED");
            runs.put("operation-pause",run);experiment.configuration.put("scenarioRuns",runs);
            return new AiTurnDecision(action("SPEAK","保存结果",null),"保存结果",Map.of(),List.of(),Map.of(),false,Map.of());
        }).when(generator).generate(any(),any(),anyString(),anyLong(),any(),anyMap(),any());
        service.process(experiment.id);var run=service.inspect(experiment.id,"operation-pause");
        assertEquals(1,run.get("nextIndex"));assertEquals("PAUSED",run.get("status"));assertEquals("ADMIN_PAUSED",run.get("reason"));
        assertEquals("ADMIN_PAUSED",experiment.reason);assertFalse(service.process(experiment.id));
    }
}
