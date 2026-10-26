package com.aisocialgame.lab;

import com.aisocialgame.dto.PlayerAction;
import com.aisocialgame.engine.v2.werewolf.WerewolfRuleSet;
import com.aisocialgame.integration.grpc.client.AiGrpcClient;
import com.aisocialgame.integration.grpc.dto.AiChatResult;
import com.aisocialgame.model.GameState;
import com.aisocialgame.repository.*;
import com.aisocialgame.service.RoomCodeGenerator;
import com.aisocialgame.service.ai.config.AiModelConfigService;
import com.aisocialgame.service.ai.config.AiModelSelection;
import com.aisocialgame.service.ai.v2.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.aisocialgame.engine.v2.RuleSupport.*;
import static com.aisocialgame.lab.LabTrainingFixture.error;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Cross-boundary regressions: recovery spends nothing and stopped games release their lab slots. */
class LabTrainingRecoveryTest {
    private static class Fixture extends LabTrainingFixture {
        final AiTurnGenerator generator=mock(AiTurnGenerator.class);
        final AiGrpcClient client=mock(AiGrpcClient.class);
        final LabEnrollmentRepository enrollments=mock(LabEnrollmentRepository.class);
        final RoomRepository rooms=mock(RoomRepository.class);
        final GameStateRepository states=mock(GameStateRepository.class);
        final AiTurnJobRepository jobs=mock(AiTurnJobRepository.class);
        final RoomCodeGenerator codes=mock(RoomCodeGenerator.class);
        final AiModelConfigService models=mock(AiModelConfigService.class);
        final Map<String,GameState> stateRows=new LinkedHashMap<>();
        Fixture() {
            when(states.findById(anyString())).thenAnswer(c->Optional.ofNullable(stateRows.get(c.getArgument(0))));
            when(matches.findByExperimentIdAndSequenceNumber(anyString(),anyInt())).thenAnswer(c->matchRows.stream()
                    .filter(m->m.experimentId.equals(c.getArgument(0))&&m.sequenceNumber==(int)c.getArgument(1)).findFirst());
            when(matches.countByStatusIn(anyCollection())).thenAnswer(c->matchRows.stream()
                    .filter(m->((Collection<?>)c.getArgument(0)).contains(m.status)).count());
            when(matches.save(any())).thenAnswer(c->c.getArgument(0));
            when(matches.saveAndFlush(any())).thenAnswer(c->c.getArgument(0));
        }
        LabTrainingWorker worker(){return new LabTrainingWorker(service,generator,new WerewolfRuleSet(),client,new LabTrainingInput());}
        LabRuntime runtime(){return new LabRuntime(experiments,matches,enrollments,evaluations,rooms,states,jobs,codes,game,models,budgets);}
        LabTrainingTask teaching(LabTrainingRun run) {
            var item=item("DEVELOPMENT","development","ai1");
            task(run,item,"PRACTICE","BASELINE").status="SUCCEEDED";
            var task=task(run,item,"TEACH",null);
            task.input.put("messages",List.of(Map.of("role","user","content","original frozen input")));
            task.requestId="original-"+task.id;task.checkpoint.put("originalRequestId",task.requestId);
            task.status="WAITING";return task;
        }
        void exhausted(){doThrow(error(LabBudgetService.EXHAUSTED)).when(budgets).requireRemaining(experiment);}
        LabMatch match(String status,String runId) {
            var match=new LabMatch();match.experimentId=experiment.id;match.roomId="room-"+match.id;
            match.status=status;match.sequenceNumber=matchRows.size()+1;
            if(runId!=null)match.snapshot.put("trainingRunId",runId);
            matchRows.add(match);experiment.nextSequence=match.sequenceNumber+1;return match;
        }
        GameState state(LabMatch match,String phase) {
            var state=new GameState(match.roomId,"werewolf",phase);state.getData().put("archiveId","archive-"+match.id);
            state.getData().put("winner","GOOD");stateRows.put(match.roomId,state);return state;
        }
    }
    private static String reply(){return "{\"diagnosis\":{\"category\":\"REASONABLE\",\"summary\":\"保留有依据的原判断\",\"evidenceIds\":[\"e1\"]},\"changes\":[]}";}

    @Test void pausedExhaustedCourseParsesStoredReplyAfterRestartWithoutSendingOrAdvancing() {
        var f=new Fixture();var run=f.run();run.status="PAUSED";run.reason=LabBudgetService.EXHAUSTED;f.exhausted();
        var task=f.teaching(run);task.status="RUNNING";task.leaseUntilEpochMs=System.currentTimeMillis()-1;
        task.checkpoint.put("response",Map.of("content",reply(),"modelKey","coach","promptTokens",5,"completionTokens",3,"finishReason","stop"));
        var later=f.task(run,f.item("DEVELOPMENT","next-source","ai2"),"PRACTICE","BASELINE");
        assertTrue(f.worker().process(f.experiment.id));
        assertEquals("SUCCEEDED",task.status);assertEquals(reply(),task.result.get("rawReply"));
        assertEquals("PAUSED",run.status);assertEquals(LabBudgetService.EXHAUSTED,run.reason);assertEquals("PRACTICE",run.stage);
        assertEquals("PENDING",later.status);assertFalse(f.worker().process(f.experiment.id));
        verify(f.budgets,never()).requireRemaining(any());verifyNoInteractions(f.client,f.generator);
        verify(f.strategies,never()).createTrainingVersion(any(),any(),anyMap());
    }

    @Test void pausedExhaustedCourseQueriesOriginalCoachResultAndKeepsTheOriginalIdentity() {
        var f=new Fixture();var run=f.run();run.status="PAUSED";run.reason="ADMIN_PAUSED";f.exhausted();
        var task=f.teaching(run);String original=task.requestId;
        when(f.client.recoverChatCompletions(anyString(),anyLong(),eq(original))).thenReturn(new AiChatResult(reply(),"coach",5,3));
        assertTrue(f.worker().process(f.experiment.id));assertEquals("SUCCEEDED",task.status);assertEquals(original,task.requestId);
        assertEquals("PAUSED",run.status);assertEquals("ADMIN_PAUSED",run.reason);assertFalse(f.worker().process(f.experiment.id));
        verify(f.client,times(1)).recoverChatCompletions(anyString(),anyLong(),eq(original));
        verify(f.client,never()).chatCompletions(anyString(),anyLong(),anyString(),any(AiModelSelection.class),anyList(),anyString(),anyInt());
        verify(f.budgets,never()).requireRemaining(any());assertEquals(0,task.paidRetries);
    }

    @Test void reachingBudgetSkipsNewTaskButStillRecoversLaterAlreadySentTaskInTheSamePass() {
        var f=new Fixture();var run=f.run();f.exhausted();
        var fresh=f.task(run,f.item("DEVELOPMENT","new-source","ai2"),"PRACTICE","BASELINE");
        var original=f.teaching(run);
        when(f.client.recoverChatCompletions(anyString(),anyLong(),eq(original.requestId))).thenReturn(new AiChatResult(reply(),"coach",5,3));
        assertTrue(f.worker().process(f.experiment.id));
        assertEquals("PAUSED",run.status);assertEquals(LabBudgetService.EXHAUSTED,run.reason);
        assertEquals("PENDING",fresh.status);assertTrue(fresh.input.isEmpty());assertEquals("SUCCEEDED",original.status);
        verifyNoInteractions(f.generator);verify(f.budgets,times(1)).requireRemaining(f.experiment);
        verify(f.client,never()).chatCompletions(anyString(),anyLong(),anyString(),any(AiModelSelection.class),anyList(),anyString(),anyInt());
    }

    @Test void pausedPlayerTaskKeepsItsOriginalCheckpointAndNeverStartsAnUnsentTask() {
        var f=new Fixture();var run=f.run();run.status="PAUSED";f.exhausted();
        var item=f.item("DEVELOPMENT","original-source","ai1");var task=f.task(run,item,"PRACTICE","BASELINE");
        task.input.put("observation",item.data.get("observation"));task.input.put("usedStrategy",Map.of());
        var checkpoint=Map.<String,Object>of("originalRequestId","player-original","lastCallCode","AI_RESULT_PENDING","modelSelection",f.player);
        task.checkpoint.put("playerCheckpoint",checkpoint);task.checkpoint.put("remainingMillis",0L);task.requestId="player-original";task.status="WAITING";
        var next=f.task(run,f.item("DEVELOPMENT","next-source","ai2"),"PRACTICE","BASELINE");
        var action=new PlayerAction();action.setType("SPEAK");action.setContent("依据公开事实保留判断。");
        when(f.generator.generate(any(),any(),eq(task.id),eq(0L),any(),eq(checkpoint),any()))
                .thenReturn(new AiTurnDecision(action,action.getContent(),Map.of(),List.of(),Map.of(),false,Map.of("usageComplete",true)));
        assertTrue(f.worker().process(f.experiment.id));assertEquals("SUCCEEDED",task.status);assertEquals("player-original",task.requestId);
        assertEquals("PAUSED",run.status);assertEquals("PENDING",next.status);assertFalse(f.worker().process(f.experiment.id));
        verify(f.generator,times(1)).generate(any(),any(),eq(task.id),eq(0L),any(),eq(checkpoint),any());
        verify(f.budgets,never()).requireRemaining(any());verifyNoInteractions(f.client);
    }

    @Test void pausedConfirmedUnsentTaskCannotBeMistakenForReadOnlyRecovery() {
        var f=new Fixture();var run=f.run();run.status="PAUSED";
        var task=f.task(run,f.item("DEVELOPMENT","source","ai1"),"PRACTICE","BASELINE");
        task.status="WAITING";task.requestId="unsent-original";
        task.checkpoint.put("playerCheckpoint",Map.of("originalRequestId","unsent-original","lastCallCode","AI_REQUEST_NOT_SENT","transportAttempt",1));
        assertFalse(f.worker().process(f.experiment.id));assertEquals("WAITING",task.status);assertEquals("unsent-original",task.requestId);
        verifyNoInteractions(f.client,f.generator);verify(f.budgets,never()).requireRemaining(any());
    }

    @Test void stoppingCourseWithWaitingAndPlayingMatchesReleasesBothSlotsAndAllowsANewCourse() {
        var f=new Fixture();var run=f.run();run.stage="GAMES";f.experiment.configuration.put("trainingV2Enabled",true);
        var waiting=f.match("WAITING",run.id);var playing=f.match("PLAYING",run.id);var state=f.state(playing,"DAY_DISCUSS");
        var evaluation=new LabEvaluation();evaluation.experimentId=f.experiment.id;evaluation.configuration.put("trainingRunId",run.id);f.evaluationRows.add(evaluation);
        waiting.evaluationId=evaluation.id;playing.evaluationId=evaluation.id;
        when(f.game.stopLabBudget(playing.roomId)).thenAnswer(c->{state.setPhase("SETTLEMENT");state.getData().put("labFailureReason","LAB_STOPPED_BY_ADMIN");return text(state.getData().get("archiveId"));});
        assertEquals(2L,f.matches.countByStatusIn(LabRuntime.ACTIVE));
        f.service.control(f.experiment.id,run.operationId,"stop");
        assertEquals("STOPPED",evaluation.status);assertEquals(true,waiting.snapshot.get("trainingStopped"));assertEquals(true,playing.snapshot.get("trainingStopped"));
        f.runtime().advance(f.experiment.id);
        assertEquals("ABORTED",waiting.status);assertEquals("ADMIN_STOPPED",waiting.reason);
        assertEquals("ABORTED",playing.status);assertEquals("LAB_STOPPED_BY_ADMIN",playing.reason);
        assertEquals(state.getData().get("archiveId"),playing.archiveId);assertNotNull(playing.finishedAt);
        assertEquals(0L,f.matches.countByStatusIn(LabRuntime.ACTIVE));
        var finished=playing.finishedAt;f.runtime().advance(f.experiment.id);assertEquals(finished,playing.finishedAt);
        verify(f.matches,times(1)).saveAndFlush(playing);verify(f.game,never()).startLab(anyString());
        var next=f.service.create(f.experiment.id,new LabTrainingService.Request("after-stopped-course","RULES_EVIDENCE",null));
        assertEquals("DRAFT",next.get("status"));assertEquals(2,f.runRows.size());verifyNoInteractions(f.client,f.generator);
    }

    @Test void legacyEvaluationStillCollectsNormalSettlementAndCompletesItsPairExactlyOnce() {
        var f=new Fixture();f.experiment.status="RUNNING";f.experiment.configuration.put("gameId","undercover");
        var evaluation=new LabEvaluation();evaluation.experimentId=f.experiment.id;evaluation.pairs=1;evaluation.nextGame=2;
        evaluation.configuration.put("returnStatus","PAUSED");f.evaluationRows.add(evaluation);
        var baseline=f.match("REVIEWED",null);baseline.evaluationId=evaluation.id;baseline.archiveId="baseline-archive";
        var candidate=f.match("PLAYING",null);candidate.evaluationId=evaluation.id;var state=f.state(candidate,"SETTLEMENT");state.setGameId("undercover");
        f.runtime().advance(f.experiment.id);
        assertEquals("REVIEWED",candidate.status);assertNull(candidate.reason);assertEquals(state.getData().get("archiveId"),candidate.archiveId);
        assertEquals("baseline-archive",baseline.archiveId);assertEquals("COMPLETED",evaluation.status);assertEquals("PAUSED",f.experiment.status);
        assertEquals(f.experiment.nextSequence,f.experiment.reviewCursor);assertEquals(0L,f.matches.countByStatusIn(LabRuntime.ACTIVE));
        var finished=candidate.finishedAt;f.runtime().advance(f.experiment.id);assertEquals(finished,candidate.finishedAt);
        verify(f.matches,times(1)).saveAndFlush(candidate);verify(f.game,never()).startLab(anyString());
        assertEquals(2,f.matchRows.size());
    }

    @Test void discardingOneExplicitPairIsIdempotentKeepsScenarioScoresAndNeverDiscardsANewerPair() {
        var f=new Fixture();var run=f.run();run.stage="GAMES";run.status="PAUSED";run.assessment.put("baselineErrors",6);run.assessment.put("candidateErrors",4);
        var evaluation=new LabEvaluation();evaluation.experimentId=f.experiment.id;evaluation.configuration.put("trainingRunId",run.id);f.evaluationRows.add(evaluation);
        var match=f.match("PLAYING",run.id);match.evaluationId=evaluation.id;
        when(f.game.discardLabEvaluation(match.roomId)).thenReturn("aborted-archive");
        f.service.discardGamePair(f.experiment.id,run.operationId,evaluation.id);
        assertEquals("ABORTED",match.status);assertEquals("aborted-archive",match.archiveId);assertEquals("STOPPED",evaluation.status);
        assertEquals(true,evaluation.configuration.get("invalidated"));assertEquals("PAUSED",run.status);assertEquals(4,run.assessment.get("candidateErrors"));
        var newer=new LabEvaluation();newer.experimentId=f.experiment.id;newer.configuration.put("trainingRunId",run.id);f.evaluationRows.add(newer);
        f.service.discardGamePair(f.experiment.id,run.operationId,evaluation.id);
        assertEquals("RUNNING",newer.status);verify(f.game,times(1)).discardLabEvaluation(match.roomId);verifyNoInteractions(f.client,f.generator);
        assertThrows(com.aisocialgame.exception.ApiException.class,()->f.service.discardGamePair(f.experiment.id,run.operationId,"another-run-pair"));
    }
}
