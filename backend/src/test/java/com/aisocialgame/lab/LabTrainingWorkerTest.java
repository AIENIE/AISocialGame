package com.aisocialgame.lab;

import com.aisocialgame.engine.v2.werewolf.WerewolfRuleSet;
import com.aisocialgame.integration.grpc.client.AiGrpcClient;
import com.aisocialgame.integration.grpc.dto.AiChatResult;
import com.aisocialgame.service.ai.config.AiModelSelection;
import com.aisocialgame.service.ai.v2.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.*;
import java.util.function.Consumer;
import static com.aisocialgame.engine.v2.RuleSupport.*;
import static com.aisocialgame.lab.LabTrainingFixture.error;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LabTrainingWorkerTest {
    private static class Fixture extends LabTrainingFixture {
        final AiTurnGenerator generator=mock(AiTurnGenerator.class);
        final AiGrpcClient client=mock(AiGrpcClient.class);
        final LabTrainingInput input=new LabTrainingInput();
        LabTrainingWorker worker(){return new LabTrainingWorker(service,generator,new WerewolfRuleSet(),client,input);}
        LabTrainingTask teaching(LabTrainingRun run) {
            var item=item("DEVELOPMENT","development","ai1");var practice=task(run,item,"PRACTICE","BASELINE");practice.status="SUCCEEDED";
            var teach=task(run,item,"TEACH",null);teach.input.put("payload",Map.of("validEvidenceIds",List.of("e1")));teach.input.put("messages",List.of(Map.of("role","user","content","fixture")));return teach;
        }
        void authoritative(String state,Long endedAt) {
            var response=new LinkedHashMap<String,Object>();response.put("state","RECONCILIATION_REQUIRED");response.put("executionState",state);if(endedAt!=null)response.put("executionEndedAtEpochMs",endedAt);
            when(calls.inspectExecution(anyString(),anyLong(),anyString())).thenReturn(response);
        }
        void failCoach() {when(client.chatCompletions(anyString(),anyLong(),anyString(),any(AiModelSelection.class),anyList(),anyString(),anyInt())).thenThrow(error("AI_USAGE_UNKNOWN"));}
        void goodCoach(){when(client.chatCompletions(anyString(),anyLong(),anyString(),any(AiModelSelection.class),anyList(),anyString(),anyInt())).thenReturn(new AiChatResult(reply(),"coach",5,3));}
    }
    private static String reply(){return "{\"diagnosis\":{\"category\":\"REASONABLE\",\"summary\":\"依据可见记录作答\",\"evidenceIds\":[\"e1\"]},\"changes\":[]}";}

    @Test void unknownCoachExecutionOnlyQueriesOriginalAndBlocksAnotherCoachOnTheSameLane() {
        var f=new Fixture();var run=f.run();var first=f.teaching(run);var second=f.teaching(run);f.failCoach();
        assertTrue(f.worker().process(f.experiment.id));assertEquals("WAITING",first.status);assertEquals(0,first.paidRetries);assertNotNull(first.requestId);
        String original=first.requestId;first.notBeforeEpochMs=0;
        when(f.client.recoverChatCompletions(anyString(),anyLong(),eq(original))).thenThrow(error("AI_USAGE_UNKNOWN"));
        assertTrue(f.worker().process(f.experiment.id));assertEquals(original,first.requestId);assertEquals("PENDING",second.status);
        first.notBeforeEpochMs=Long.MAX_VALUE;assertFalse(f.worker().process(f.experiment.id));
        verify(f.client,times(1)).chatCompletions(anyString(),anyLong(),anyString(),any(AiModelSelection.class),anyList(),anyString(),anyInt());
        verify(f.client,times(1)).recoverChatCompletions(anyString(),anyLong(),eq(original));
    }
    @Test void paidRedoRequiresTerminatedExecutionWithAuthoritativeEndTime() {
        for(String execution:List.of("UNKNOWN","RUNNING","NOT_STARTED","TERMINATED")) {
            var f=new Fixture();var run=f.run();var task=f.teaching(run);f.failCoach();f.authoritative(execution,null);
            f.worker().process(f.experiment.id);assertEquals(0,task.paidRetries);assertEquals("WAITING",task.status);
        }
    }
    @Test void confirmedUnsentOnlyChangesTransportWithBoundedBackoffAndUsesNoPaidRedo() {
        var f=new Fixture();var run=f.run();var task=f.teaching(run);
        when(f.client.chatCompletions(anyString(),anyLong(),anyString(),any(AiModelSelection.class),anyList(),anyString(),anyInt())).thenThrow(error("AI_REQUEST_NOT_SENT"));
        for(int attempt=0;attempt<4;attempt++) {
            task.notBeforeEpochMs=0;long before=System.currentTimeMillis();f.worker().process(f.experiment.id);
            assertEquals(0,task.paidRetries);assertEquals(0,task.attempt);assertNull(task.requestId);
            if(attempt<3) {assertEquals("WAITING",task.status);assertTrue(task.notBeforeEpochMs>=before+new long[]{2000,5000,10000}[attempt]);}
            else {assertEquals("FAILED",task.status);assertEquals("LAB_CALL_RETRIES_EXHAUSTED",task.reason);}
        }
        var ids=ArgumentCaptor.forClass(String.class);verify(f.client,times(4)).chatCompletions(anyString(),anyLong(),anyString(),any(AiModelSelection.class),anyList(),ids.capture(),anyInt());
        String prefix="lab-training:"+task.id+":0";assertEquals(List.of(prefix,prefix+":transport:1",prefix+":transport:2",prefix+":transport:3"),ids.getAllValues());
        verify(f.calls,never()).inspectExecution(anyString(),anyLong(),anyString());
    }
    @Test void originalWithoutProgressBecomesBlockedAfterTenMinutesAndRetainsItsIdentity() {
        var f=new Fixture();var run=f.run();var task=f.teaching(run);task.status="WAITING";task.requestId="original";
        task.checkpoint.put("originalRequestId","original");task.checkpoint.put("waitingSinceEpochMs",System.currentTimeMillis()-LabCallRecovery.MAX_WAIT_MS-1);
        when(f.client.recoverChatCompletions(anyString(),anyLong(),eq("original"))).thenThrow(error("AI_USAGE_UNKNOWN"));
        f.worker().process(f.experiment.id);assertEquals("BLOCKED",task.status);assertEquals("LAB_CALL_NO_PROGRESS",task.reason);assertEquals("LAB_CALL_NO_PROGRESS",run.reason);assertEquals("original",task.requestId);
        assertFalse(f.worker().process(f.experiment.id));verify(f.client,times(1)).recoverChatCompletions(anyString(),anyLong(),eq("original"));
        verify(f.client,never()).chatCompletions(anyString(),anyLong(),anyString(),any(AiModelSelection.class),anyList(),anyString(),anyInt());
    }
    @Test void onePaidRedoWaitsSixtySecondsPreservesOriginalAndNeverMakesAThirdPaidAttempt() {
        var f=new Fixture();var run=f.run();var task=f.teaching(run);f.failCoach();f.authoritative("TERMINATED",System.currentTimeMillis()-10_000);
        long before=System.currentTimeMillis();f.worker().process(f.experiment.id);
        assertEquals(1,task.paidRetries);assertEquals(1,task.attempt);assertEquals("PAID_REDO_WAIT",task.reason);assertTrue(task.notBeforeEpochMs>=before+60_000);
        assertNull(task.requestId);assertEquals(1,maps(task.checkpoint.get("attemptHistory")).size());
        assertFalse(f.worker().process(f.experiment.id));task.notBeforeEpochMs=0;f.worker().process(f.experiment.id);
        assertEquals("FAILED",task.status);assertEquals(1,task.paidRetries);assertEquals(1,run.configuration.get("paidRetriesUsed"));
        var ids=ArgumentCaptor.forClass(String.class);verify(f.client,times(2)).chatCompletions(anyString(),anyLong(),anyString(),any(AiModelSelection.class),anyList(),ids.capture(),anyInt());
        assertNotEquals(ids.getAllValues().getFirst(),ids.getAllValues().getLast());assertEquals(ids.getAllValues(),strings(task.checkpoint.get("requestHistory")));
    }
    @Test void globalPaidRedoAllowanceIsTwoAcrossDifferentCoachTasks() {
        var f=new Fixture();var run=f.run();var first=f.teaching(run);var second=f.teaching(run);var third=f.teaching(run);f.failCoach();f.authoritative("TERMINATED",System.currentTimeMillis()-1);
        f.worker().process(f.experiment.id);f.worker().process(f.experiment.id);f.worker().process(f.experiment.id);
        assertEquals(1,first.paidRetries);assertEquals(1,second.paidRetries);assertEquals(0,third.paidRetries);assertEquals("FAILED",third.status);assertEquals(2,run.configuration.get("paidRetriesUsed"));
        verify(f.client,times(3)).chatCompletions(anyString(),anyLong(),anyString(),any(AiModelSelection.class),anyList(),anyString(),anyInt());
    }
    @Test void restartedTaskWithAStoredSuccessfulReplyNeverRegeneratesAndSuccessIsNotProcessedAgain() {
        var f=new Fixture();var run=f.run();var task=f.teaching(run);task.status="RUNNING";task.leaseUntilEpochMs=System.currentTimeMillis()-1;task.requestId="original";
        task.checkpoint.put("originalRequestId","original");task.checkpoint.put("response",Map.of("content",reply(),"modelKey","coach","promptTokens",5,"completionTokens",3,"finishReason","stop"));
        assertTrue(f.worker().process(f.experiment.id));assertEquals("SUCCEEDED",task.status);assertEquals("REASONABLE",map(task.result.get("diagnosis")).get("category"));
        assertFalse(f.worker().process(f.experiment.id));verifyNoInteractions(f.client);verify(f.strategies,never()).createTrainingVersion(any(),any(),anyMap());
    }
    @Test void restartWithOnlyAnOriginalRequestRecoversInsteadOfSendingAndKeepsTheReply() {
        var f=new Fixture();var run=f.run();var task=f.teaching(run);task.status="WAITING";task.requestId="original";task.checkpoint.put("originalRequestId","original");
        when(f.client.recoverChatCompletions(anyString(),anyLong(),eq("original"))).thenReturn(new AiChatResult(reply(),"coach",5,3));
        f.worker().process(f.experiment.id);assertEquals("SUCCEEDED",task.status);assertEquals(reply(),task.result.get("rawReply"));
        verify(f.client).recoverChatCompletions(anyString(),anyLong(),eq("original"));verify(f.client,never()).chatCompletions(anyString(),anyLong(),anyString(),any(AiModelSelection.class),anyList(),anyString(),anyInt());
    }
    @Test void budgetPauseBeforeClaimPreservesTaskAndDoesNotSend() {
        var f=new Fixture();var run=f.run();var task=f.teaching(run);doThrow(error(LabBudgetService.EXHAUSTED)).when(f.budgets).requireRemaining(f.experiment);
        assertFalse(f.worker().process(f.experiment.id));assertEquals("PAUSED",run.status);assertEquals(LabBudgetService.EXHAUSTED,run.reason);assertEquals("PENDING",task.status);
        verifyNoInteractions(f.client,f.generator);
    }
    @Test void playerCheckpointAndBudgetDeferralArePersistedWithoutLosingTheRequestIdentity() {
        var f=new Fixture();var run=f.run();var item=f.item("DEVELOPMENT","development","ai1");var task=f.task(run,item,"PRACTICE","BASELINE");
        when(f.generator.generate(any(),any(),anyString(),anyLong(),any(),anyMap(),any())).thenAnswer(c->{
            Consumer<Map<String,Object>> save=c.getArgument(6);save.accept(Map.of("originalRequestId","player-original","actualPrompt",List.of(Map.of("role","user","content","exact")),"encoded",Map.of("format","fixture"),"codePoints",100));
            throw new AiTurnGenerator.GenerationDeferred(Map.of("originalRequestId","player-original"),LabBudgetService.EXHAUSTED);
        });
        f.worker().process(f.experiment.id);assertEquals("PAUSED",run.status);assertEquals("WAITING",task.status);assertEquals("player-original",task.requestId);
        assertEquals(100,task.input.get("codePoints"));assertEquals("exact",text(maps(task.input.get("actualPrompt")).getFirst().get("content")));
    }
    @Test void representativePausesOnlyAfterBothItsPracticeAndTeachingSucceed() {
        var f=new Fixture();var run=f.run();run.configuration.put("representativeOnly",true);var teach=f.teaching(run);f.goodCoach();
        var other=f.item("DEVELOPMENT","development","ai2");var later=f.task(run,other,"PRACTICE","BASELINE");
        f.worker().process(f.experiment.id);assertEquals("SUCCEEDED",teach.status);assertEquals("RUNNING",run.status);
        assertFalse(f.worker().process(f.experiment.id));assertEquals("PAUSED",run.status);assertEquals("REPRESENTATIVE_CASE_READY",run.reason);assertEquals("PENDING",later.status);verifyNoInteractions(f.generator);
    }
    @Test void representativeOnlyNeverStartsTheNextCaseWhileFirstTeachingWaitsForOriginalOrPaidRedo() {
        for(String reason:List.of("AI_USAGE_UNKNOWN","PAID_REDO_WAIT")) {
            var f=new Fixture();var run=f.run();run.configuration.put("representativeOnly",true);var first=f.teaching(run);
            first.reason=reason;first.status="PAID_REDO_WAIT".equals(reason)?"PENDING":"WAITING";first.notBeforeEpochMs=System.currentTimeMillis()+60_000;
            if("AI_USAGE_UNKNOWN".equals(reason)){first.requestId="first-original";first.checkpoint.put("originalRequestId","first-original");first.checkpoint.put("executionState","UNKNOWN");}
            var secondItem=f.item("DEVELOPMENT","second-source","ai2");var second=f.task(run,secondItem,"PRACTICE","BASELINE");
            assertFalse(f.worker().process(f.experiment.id));assertEquals("PENDING",second.status);assertEquals("RUNNING",run.status);assertEquals(true,run.configuration.get("representativeOnly"));
            assertNull(second.startedAtEpochMs);assertNull(second.requestId);verifyNoInteractions(f.generator,f.client);
        }
    }
    @Test void representativeMissingTasksOrFailedTeachingCannotBeReportedReady() {
        for(boolean missing:List.of(false,true)) {
            var f=new Fixture();var run=f.run();run.configuration.put("representativeOnly",true);var item=f.item("DEVELOPMENT","development","ai1");
            if(!missing) {f.task(run,item,"PRACTICE","BASELINE").status="SUCCEEDED";f.task(run,item,"TEACH",null).status="FAILED";}
            f.worker().process(f.experiment.id);assertNotEquals("REPRESENTATIVE_CASE_READY",run.reason);assertNotEquals("IMPROVED",run.assessment.get("status"));assertNull(run.candidateStrategyVersionId);
            verifyNoInteractions(f.client,f.generator);
        }
    }
    @Test void oversizedTeachingMaterialFailsBeforeSendingAndRetainsTheFullEncodedInput() {
        var f=new Fixture();var run=f.run();var task=f.teaching(run);task.input.clear();f.caseRows.get(task.caseId).data.put("actualPrompt","😀".repeat(33_000));
        f.worker().process(f.experiment.id);assertEquals("FAILED",task.status);assertEquals("MATERIAL_TOO_LARGE",task.reason);assertTrue(number(task.input.get("codePoints"),0)>32_000);
        assertEquals("😀".repeat(33_000),LabTrainingInput.decode(map(task.input.get("encoded"))).get("actualPrompt"));verifyNoInteractions(f.client);
    }
    @Test void missingOrFailedDependenciesNeverCallTheJudgeOrDeclareSuccess() {
        for(boolean failed:List.of(false,true)) {
            var f=new Fixture();var run=f.run();run.stage="HOLDOUT";var item=f.item("HOLDOUT","holdout","ai1");
            var first=f.task(run,item,"HOLDOUT","BASELINE");first.status=failed?"FAILED":"SUCCEEDED";
            if(failed)f.task(run,item,"HOLDOUT","CANDIDATE").status="SUCCEEDED";
            var judge=f.task(run,item,"JUDGE",null);f.worker().process(f.experiment.id);
            assertNotEquals("SUCCEEDED",judge.status);assertTrue(f.evaluationRows.isEmpty());verifyNoInteractions(f.client,f.generator);
            if(failed)assertEquals("DEPENDENCY_FAILED",judge.reason);else {assertEquals("BLOCKED",judge.status);assertEquals("DEPENDENCY_MISSING",judge.reason);}
        }
    }
    @Test void incompleteHoldoutNeverStartsGamesOrFeedsAssessmentBackIntoLearning() {
        var f=new Fixture();var run=f.run();run.stage="HOLDOUT";run.candidateStrategyVersionId="candidate";holdout(f,run,31);
        assertFalse(f.worker().process(f.experiment.id));assertEquals("COMPLETED",run.status);assertEquals("INCOMPLETE",run.assessment.get("status"));
        assertTrue(f.evaluationRows.isEmpty());verify(f.strategies,never()).createTrainingVersion(any(),any(),anyMap());verifyNoInteractions(f.client,f.generator);
    }
    @Test void thirtyTwoIndependentPairsWithImprovementStartOnlyTheFirstRealPairAndDoNotTeach() {
        var f=new Fixture();var run=f.run();run.stage="HOLDOUT";run.candidateStrategyVersionId="candidate";holdout(f,run,32);
        assertFalse(f.worker().process(f.experiment.id));assertEquals("GAMES",run.stage);assertEquals("IMPROVED",run.assessment.get("status"));
        assertEquals(1,f.evaluationRows.size());assertEquals("p1",f.evaluationRows.getFirst().participantId);assertEquals("VILLAGER",f.evaluationRows.getFirst().configuration.get("targetRole"));
        assertFalse(f.worker().process(f.experiment.id));assertEquals(1,f.evaluationRows.size());verify(f.strategies,never()).createTrainingVersion(any(),any(),anyMap());
        assertTrue(f.taskRows.values().stream().noneMatch(t->"TEACH".equals(t.kind)));verifyNoInteractions(f.client,f.generator);
    }
    @Test void candidateCreationFailureCannotAdvanceToRehearsalOrExposeAPartialCandidate() {
        var f=new Fixture();var run=f.run();run.stage="MERGE";var task=f.teaching(run);task.status="SUCCEEDED";
        task.result.put("changes",List.of(Map.of("operation","ADD","card",Map.of("id","one","role","GENERAL","phases",List.of(),"condition","公开证据","advice","先核实再判断","exceptions","","evidence",List.of()),"evidenceIds",List.of("e1"),"reason","fixture")));
        when(f.strategies.createTrainingVersion(any(),any(),anyMap())).thenThrow(new IllegalStateException("commit failed"));
        assertFalse(f.worker().process(f.experiment.id));assertEquals("PAUSED",run.status);assertEquals("TRAINING_STAGE_FAILED",run.reason);assertEquals("MERGE",run.stage);
        assertEquals("commit failed",map(run.assessment.get("stageFailure")).get("message"));assertNull(run.candidateStrategyVersionId);assertTrue(f.taskRows.values().stream().noneMatch(t->"REHEARSAL".equals(t.kind)));
        assertFalse(f.worker().process(f.experiment.id));verify(f.strategies,times(1)).createTrainingVersion(any(),any(),anyMap());verifyNoInteractions(f.generator,f.client);
        var version=new LabStrategyVersion();version.id="candidate";doReturn(version).when(f.strategies).createTrainingVersion(any(),any(),anyMap());
        when(f.generator.generate(any(),any(),anyString(),anyLong(),any(),anyMap(),any())).thenThrow(new AiTurnGenerator.GenerationFailure("fixture",Map.of("usageComplete",true)));
        f.service.control(f.experiment.id,run.operationId,"resume");assertEquals("RUNNING",run.status);assertNull(run.reason);
        f.worker().process(f.experiment.id);assertEquals("candidate",run.candidateStrategyVersionId);assertEquals("REHEARSAL",run.stage);
        assertEquals(1,f.taskRows.values().stream().filter(t->"REHEARSAL".equals(t.kind)).count());
    }
    private static void holdout(Fixture f,LabTrainingRun run,int count) {
        for(int i=0;i<count;i++) {
            var item=f.item("HOLDOUT","family-"+(i/4),"ai"+(i%4+1));
            f.task(run,item,"HOLDOUT","BASELINE").status="SUCCEEDED";f.task(run,item,"HOLDOUT","CANDIDATE").status="SUCCEEDED";
            var judge=f.task(run,item,"JUDGE",null);judge.status="SUCCEEDED";judge.result.put("variantByLabel",Map.of("A","BASELINE","B","CANDIDATE"));
            judge.result.put("assessments",List.of(grade("A",true),grade("B",false)));
        }
    }
    private static Map<String,Object> grade(String label,boolean error) {
        var value=new LinkedHashMap<String,Object>();value.put("label",label);value.put("facts",error?"FAIL":"PASS");value.put("evidence","PASS");value.put("response","PASS");value.put("targetErrors",error?1:0);
        value.put("ruleError",false);value.put("evidenceLeak",false);value.put("evidenceIds",List.of("e1"));value.put("reason","按材料判断");return value;
    }
}
