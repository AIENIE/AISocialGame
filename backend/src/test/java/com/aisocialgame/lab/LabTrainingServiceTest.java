package com.aisocialgame.lab;

import com.aisocialgame.config.AppProperties;
import com.aisocialgame.exception.ApiException;
import com.aisocialgame.service.ai.v2.VisibleObservation;
import com.aisocialgame.service.credit.BoundedAiClient;
import com.aisocialgame.service.v2.V2GameService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.*;
import static com.aisocialgame.engine.v2.RuleSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LabTrainingServiceTest {
    @Test void creationFreezesFortyCasesAndSixteenDevelopmentTasksAndRecoversTheSameOperation() {
        var f=new LabTrainingFixture();var request=new LabTrainingService.Request("operation-create","RULES_EVIDENCE",null);
        var first=f.service.create(f.experiment.id,request);var same=f.service.create(f.experiment.id,request);
        assertEquals(first,same);assertEquals(1,f.runRows.size());assertEquals(40,f.caseRows.size());assertEquals(16,f.taskRows.size());
        assertTrue(f.taskRows.values().stream().allMatch(t->Set.of("PRACTICE","TEACH").contains(t.kind)));
        assertEquals("DRAFT",first.get("status"));assertEquals("PAUSED",f.experiment.status);
        verify(f.factory,times(1)).build(any(),anyString());verify(f.calls,never()).preflight(any(),anyInt());
        var run=f.runRows.values().iterator().next();assertNotNull(run.configuration.get("sourceManifestHash"));
        f.experiment.configuration.put("coach",Map.of("modelKey","changed"));assertEquals("coach",map(run.configuration.get("coach")).get("modelKey"));
    }
    @Test void duplicateOperationRejectsDifferentParametersBeforeRecordingAnotherExternalCost() {
        var f=new LabTrainingFixture();f.service.create(f.experiment.id,new LabTrainingService.Request("operation-create","RULES_EVIDENCE",null));
        var error=assertThrows(ApiException.class,()->f.service.create(f.experiment.id,new LabTrainingService.Request("operation-create","RULES_EVIDENCE",
                List.of(new LabTrainingService.ExternalDiagnostic("external-request","external-budget")))));
        assertEquals("TRAINING_OPERATION_CONFLICT",error.getCode());assertEquals(1,f.runRows.size());
        verify(f.calls,never()).inspectExternalCharge(anyString(),anyLong(),anyString(),anyString());
    }
    @Test void authoritativeExternalDiagnosticIsRegisteredOnceAndUnknownHistoricalFeesRemainVisible() {
        var f=new LabTrainingFixture();when(f.calls.inspectExternalCharge(anyString(),anyLong(),eq("external-request"),eq("external-budget")))
                .thenReturn(Map.of("requestId","external-request","budgetId","external-budget","creditPoints",1));
        var request=new LabTrainingService.Request("operation-external","RULES_EVIDENCE",List.of(new LabTrainingService.ExternalDiagnostic("external-request","external-budget")));
        f.service.create(f.experiment.id,request);var recovered=f.service.create(f.experiment.id,request);
        var costs=map(recovered.get("cost"));assertEquals(233,costs.get("spentPoints"));assertEquals(1L,costs.get("externalPoints"));assertEquals(234L,costs.get("totalKnownPoints"));assertEquals(2,costs.get("pendingCalls"));
        assertEquals(1,map(f.experiment.configuration.get("externalDiagnosticCosts")).size());
        verify(f.calls,times(1)).inspectExternalCharge(anyString(),anyLong(),anyString(),anyString());
    }
    @Test void missingMaterialAndOverlappingSourcesCannotCreateAPartialCourse() {
        for(boolean overlap:List.of(false,true)) {
            var f=new LabTrainingFixture();var material=f.material();
            if(overlap)material.getLast().put("sourceGroup","development");else material.removeLast();
            when(f.factory.build(any(),anyString())).thenReturn(material);
            assertThrows(ApiException.class,()->f.service.create(f.experiment.id,new LabTrainingService.Request("operation-bad","RULES_EVIDENCE",null)));
            assertTrue(f.runRows.isEmpty());assertTrue(f.taskRows.isEmpty());assertTrue(f.caseRows.isEmpty());
            assertFalse(f.experiment.configuration.containsKey("activeTrainingRunId"));
        }
    }
    @Test void startOneThenResumeKeepsIdentityAndOriginalBlockedRequestWhileOnlyPreflighting() {
        var f=new LabTrainingFixture();var run=f.run();run.status="DRAFT";
        var item=f.item("DEVELOPMENT","development","ai1");var task=f.task(run,item,"TEACH",null);task.status="BLOCKED";task.requestId="original";task.checkpoint.put("waitingSinceEpochMs",1L);
        f.service.control(f.experiment.id,run.operationId,"start-one");assertEquals(true,run.configuration.get("representativeOnly"));assertEquals("WAITING",task.status);assertEquals("original",task.requestId);
        f.service.control(f.experiment.id,run.operationId,"pause");assertEquals("PAUSED",run.status);assertEquals("ADMIN_PAUSED",run.reason);
        f.service.control(f.experiment.id,run.operationId,"resume");assertEquals(true,run.configuration.get("representativeOnly"));assertEquals("RUNNING",run.status);assertEquals("original",task.requestId);
        verify(f.calls,times(2)).preflight(any(),eq(LabCoachLimits.MAX_OUTPUT_TOKENS));
    }
    @Test void resumeRepreparesOnlyUnsentOversizedMaterialAndKeepsTheRepresentativeCheckpoint() {
        var f=new LabTrainingFixture();var run=f.run();run.status="PAUSED";run.reason="REPRESENTATIVE_CASE_FAILED";run.configuration.put("representativeOnly",true);
        var item=f.item("DEVELOPMENT","development","ai1");
        var practice=f.task(run,item,"PRACTICE","BASELINE");practice.status="SUCCEEDED";practice.requestId="paid-player";practice.result.put("speech","preserved");
        var teach=f.task(run,item,"TEACH",null);teach.status="FAILED";teach.reason="MATERIAL_TOO_LARGE";teach.input.put("payload",Map.of("original","full material"));
        var sent=f.task(run,item,"JUDGE",null);sent.status="FAILED";sent.reason="MATERIAL_TOO_LARGE";sent.checkpoint.put("requestHistory",List.of("original-sent"));sent.input.put("payload",Map.of("keep",true));
        f.service.control(f.experiment.id,run.operationId,"resume");
        assertEquals(true,run.configuration.get("representativeOnly"));assertEquals("SUCCEEDED",practice.status);assertEquals("preserved",practice.result.get("speech"));
        assertEquals("PENDING",teach.status);assertTrue(teach.input.isEmpty());assertEquals(0,teach.paidRetries);
        assertEquals("full material",map(map(maps(teach.checkpoint.get("inputPreparationHistory")).getFirst().get("input")).get("payload")).get("original"));
        assertEquals("FAILED",sent.status);assertFalse(sent.input.isEmpty());
        run.status="PAUSED";run.reason="REPRESENTATIVE_CASE_READY";
        f.service.control(f.experiment.id,run.operationId,"resume");assertEquals(false,run.configuration.get("representativeOnly"));
    }
    @Test void exhaustedBudgetDoesNotResumeOrClearPreservedProgress() {
        var f=new LabTrainingFixture();var run=f.run();run.status="PAUSED";run.reason=LabBudgetService.EXHAUSTED;
        doThrow(LabTrainingFixture.error(LabBudgetService.EXHAUSTED)).when(f.budgets).requireRemaining(f.experiment);
        assertThrows(ApiException.class,()->f.service.control(f.experiment.id,run.operationId,"resume"));
        assertEquals("PAUSED",run.status);assertEquals(LabBudgetService.EXHAUSTED,run.reason);
    }
    @Test void stopOnlyControlsThisRunsGamesAndEvaluationsAndCannotReopenCompletedRun() {
        var f=new LabTrainingFixture();var run=f.run();run.stage="GAMES";
        var own=new LabMatch();own.experimentId=f.experiment.id;own.roomId="owned-room";own.status="PLAYING";own.snapshot.put("trainingRunId",run.id);
        var other=new LabMatch();other.experimentId=f.experiment.id;other.roomId="other-room";other.status="PLAYING";other.snapshot.put("trainingRunId","other");f.matchRows.addAll(List.of(own,other));
        var evaluation=new LabEvaluation();evaluation.experimentId=f.experiment.id;evaluation.configuration.put("trainingRunId",run.id);f.evaluationRows.add(evaluation);
        f.service.control(f.experiment.id,run.operationId,"stop");assertEquals("STOPPED",run.status);assertEquals("STOPPED",evaluation.status);
        verify(f.game).stopLabBudget("owned-room");verify(f.game,never()).stopLabBudget("other-room");
        assertThrows(ApiException.class,()->f.service.control(f.experiment.id,run.operationId,"resume"));
        run.status="COMPLETED";assertThrows(ApiException.class,()->f.service.control(f.experiment.id,run.operationId,"start-one"));
    }
    @Test void summariesAndTaskInspectionPreserveRequestHistoryWithoutGenerating() {
        var f=new LabTrainingFixture();var run=f.run();var item=f.item("DEVELOPMENT","development","ai1");var task=f.task(run,item,"TEACH",null);
        task.requestId="retry";task.checkpoint.put("requestHistory",List.of("original","retry"));task.checkpoint.put("originalRequestId","retry");
        var view=f.service.inspect(f.experiment.id,run.operationId);var calls=maps(map(maps(view.get("tasks")).getFirst().get("diagnostics")).get("calls"));
        assertEquals(List.of("original","retry"),calls.stream().map(value->text(value.get("requestId"))).toList());assertEquals(1,f.service.summaries(f.experiment.id).size());
        verify(f.calls,times(2)).inspect(anyString(),anyLong(),anyString());verify(f.calls,never()).preflight(any(),anyInt());
    }
}

/** In-memory repository substitutes. Every model or account boundary is a Mockito mock. */
class LabTrainingFixture {
    final LabTrainingRunRepository runs=mock(LabTrainingRunRepository.class);
    final LabTrainingCaseRepository cases=mock(LabTrainingCaseRepository.class);
    final LabTrainingTaskRepository tasks=mock(LabTrainingTaskRepository.class);
    final LabExperimentRepository experiments=mock(LabExperimentRepository.class);
    final LabMatchRepository matches=mock(LabMatchRepository.class);
    final LabEvaluationRepository evaluations=mock(LabEvaluationRepository.class);
    final LabStrategyService strategies=mock(LabStrategyService.class);
    final LabBudgetService budgets=mock(LabBudgetService.class);
    final BoundedAiClient calls=mock(BoundedAiClient.class);
    final LabTrainingCaseFactory factory=mock(LabTrainingCaseFactory.class);
    final V2GameService game=mock(V2GameService.class);
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    final AppProperties properties=new AppProperties();
    final LabExperiment experiment=new LabExperiment();
    final Map<String,LabTrainingRun> runRows=new LinkedHashMap<>();
    final Map<String,LabTrainingCase> caseRows=new LinkedHashMap<>();
    final Map<String,LabTrainingTask> taskRows=new LinkedHashMap<>();
    final List<LabMatch> matchRows=new ArrayList<>();
    final List<LabEvaluation> evaluationRows=new ArrayList<>();
    final Map<String,Object> player=Map.of("modelKey","player","thinkingMode","ENABLED","thinkingEffort","LOW","configVersion",0);
    final Map<String,Object> coach=Map.of("modelKey","coach","thinkingMode","ENABLED","thinkingEffort","MAX","configVersion",0);
    final LabTrainingService service;
    LabTrainingFixture() {
        properties.setProjectKey("unit-test");properties.getAi().setSystemUserId(1);
        experiment.status="PAUSED";experiment.budgetPoints=3000;experiment.configuration.put("gameId","werewolf");experiment.configuration.put("playerCount",9);
        experiment.configuration.put("coach",coach);experiment.configuration.put("rules",Map.of("playerCount",9));
        var roster=new ArrayList<Map<String,Object>>();for(int i=0;i<9;i++)roster.add(Map.of("personaId","ai"+(i%4+1),"participantId","p"+(i+1),"agentId","a"+(i+1),"modelSelection",player));experiment.configuration.put("roster",roster);
        when(experiments.findById(experiment.id)).thenReturn(Optional.of(experiment));when(experiments.lock(experiment.id)).thenReturn(Optional.of(experiment));
        when(runs.findById(anyString())).thenAnswer(c->Optional.ofNullable(runRows.get(c.getArgument(0))));when(runs.lock(anyString())).thenAnswer(c->Optional.ofNullable(runRows.get(c.getArgument(0))));
        when(runs.findByExperimentIdAndOperationId(anyString(),anyString())).thenAnswer(c->runRows.values().stream().filter(r->r.experimentId.equals(c.getArgument(0))&&r.operationId.equals(c.getArgument(1))).findFirst());
        when(runs.findByExperimentIdOrderByCreatedAtDesc(anyString())).thenAnswer(c->new ArrayList<>(runRows.values()));
        when(runs.saveAndFlush(any())).thenAnswer(c->{LabTrainingRun row=c.getArgument(0);runRows.put(row.id,row);return row;});
        when(tasks.findById(anyString())).thenAnswer(c->Optional.ofNullable(taskRows.get(c.getArgument(0))));when(tasks.lock(anyString())).thenAnswer(c->Optional.ofNullable(taskRows.get(c.getArgument(0))));
        when(tasks.findByRunIdOrderByOrdinalAsc(anyString())).thenAnswer(c->taskRows.values().stream().filter(t->t.runId.equals(c.getArgument(0))).sorted(Comparator.comparingInt(t->t.ordinal)).toList());
        when(tasks.findByRunIdAndTaskKey(anyString(),anyString())).thenAnswer(c->taskRows.values().stream().filter(t->t.runId.equals(c.getArgument(0))&&t.taskKey.equals(c.getArgument(1))).findFirst());
        when(tasks.save(any())).thenAnswer(c->{LabTrainingTask row=c.getArgument(0);taskRows.put(row.id,row);return row;});when(tasks.saveAndFlush(any())).thenAnswer(c->{LabTrainingTask row=c.getArgument(0);taskRows.put(row.id,row);return row;});
        when(cases.findById(anyString())).thenAnswer(c->Optional.ofNullable(caseRows.get(c.getArgument(0))));when(cases.findByRunIdOrderByOrdinalAsc(anyString())).thenAnswer(c->caseRows.values().stream().filter(t->t.runId.equals(c.getArgument(0))).toList());
        when(cases.save(any())).thenAnswer(c->{LabTrainingCase row=c.getArgument(0);caseRows.put(row.id,row);return row;});
        when(matches.findByExperimentIdAndStatusInOrderBySequenceNumberAsc(anyString(),anyList())).thenAnswer(c->matchRows.stream().filter(m->((List<?>)c.getArgument(1)).contains(m.status)).toList());
        when(matches.findByEvaluationIdOrderBySequenceNumberAsc(anyString())).thenAnswer(c->matchRows.stream().filter(m->Objects.equals(m.evaluationId,c.getArgument(0))).toList());
        when(evaluations.findByExperimentIdOrderByCreatedAtDesc(anyString())).thenAnswer(c->new ArrayList<>(evaluationRows));when(evaluations.saveAndFlush(any())).thenAnswer(c->{LabEvaluation row=c.getArgument(0);evaluationRows.add(row);return row;});
        when(factory.build(any(),anyString())).thenAnswer(c->material());
        when(budgets.totals(anyString())).thenReturn(Map.of("spentPoints",233,"pendingCalls",2,"totalCalls",63,"actualCost",2.33));
        when(budgets.totalKnownPoints(experiment)).thenAnswer(c->233L+LabBudgetService.externalPoints(experiment));
        when(calls.inspect(anyString(),anyLong(),anyString())).thenAnswer(c->Map.of("requestId",c.getArgument(2),"state","RECONCILIATION_REQUIRED"));
        when(calls.inspectExecution(anyString(),anyLong(),anyString())).thenReturn(Map.of("executionState","UNKNOWN","state","RECONCILIATION_REQUIRED"));
        when(strategies.strategySnapshot(anyString())).thenReturn(Map.of("id","candidate","content",Map.of("cards",List.of())));
        service=new LabTrainingService(runs,cases,tasks,experiments,matches,evaluations,strategies,budgets,calls,properties,json,mock(PlatformTransactionManager.class),factory,game);
    }
    LabTrainingRun run() {
        var row=new LabTrainingRun();row.experimentId=experiment.id;row.operationId="operation-"+runRows.size();row.status="RUNNING";row.configuration.put("coach",coach);row.configuration.put("roster",experiment.configuration.get("roster"));row.configuration.put("baseStrategy",Map.of());
        runRows.put(row.id,row);experiment.configuration.put("activeTrainingRunId",row.id);return row;
    }
    LabTrainingCase item(String purpose,String group,String persona) {
        var row=new LabTrainingCase();row.runId=text(experiment.configuration.get("activeTrainingRunId"));row.purpose=purpose;row.sourceType="CONSTRUCTED";row.sourceGroup=group;row.personaId=persona;row.participantId="p1";row.contentHash="hash";
        var observation=new VisibleObservation("werewolf","fixture","DAY_DISCUSS",1,"actor","SPEAK",Map.of("role","VILLAGER"),List.of(),Map.of("playerCount",9),List.of(),Map.of(),List.of(),Map.of(),Map.of(),List.of());
        row.data.put("observation",json.convertValue(observation,Map.class));row.data.put("validEvidenceIds",List.of("e1"));row.data.put("modelSelection",player);caseRows.put(row.id,row);return row;
    }
    LabTrainingTask task(LabTrainingRun run,LabTrainingCase item,String kind,String variant) {
        var row=new LabTrainingTask();row.runId=run.id;row.caseId=item.id;row.kind=kind;row.variant=variant;row.ordinal=taskRows.size();row.taskKey=item.id+":"+kind+":"+text(variant);taskRows.put(row.id,row);return row;
    }
    List<Map<String,Object>> material() {
        var result=new ArrayList<Map<String,Object>>();for(int i=0;i<40;i++){var row=new LinkedHashMap<String,Object>();row.put("id","case-"+i);row.put("purpose",i<8?"DEVELOPMENT":"HOLDOUT");row.put("sourceType","CONSTRUCTED");row.put("sourceGroup",i<8?"development":"holdout-"+(i-8)/4);row.put("personaId","ai"+(i%4+1));row.put("participantId","p1");row.put("contentHash","hash-"+i);row.put("data",Map.of());result.add(row);}return result;
    }
    static ApiException error(String code){return new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"fixture",code,Map.of());}
}
