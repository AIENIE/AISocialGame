package com.aisocialgame;

import com.aisocialgame.engine.v2.werewolf.WerewolfAiClock;
import com.aisocialgame.integration.grpc.client.AiGrpcClient;
import com.aisocialgame.lab.LabBudgetService;
import com.aisocialgame.model.*;
import com.aisocialgame.repository.*;
import com.aisocialgame.service.ai.v2.*;
import com.aisocialgame.service.v2.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.time.LocalDateTime;
import java.util.*;
import static com.aisocialgame.engine.v2.RuleSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real persisted scheduler/clock transitions; the gateway is a test double throughout. */
@SpringBootTest(classes=AiSocialGameApplication.class) @ActiveProfiles("test")
class WerewolfLabRuntimeIntegrationTest {
    @Autowired V2GameService runtime;
    @Autowired AiTurnCoordinator coordinator;
    @Autowired RoomRepository rooms;
    @Autowired GameStateRepository states;
    @Autowired AiTurnJobRepository jobs;
    @MockitoBean AiGrpcClient client;
    @MockitoBean LabBudgetService budgets;

    private Room room(String mode) {
        var room=new Room(UUID.randomUUID().toString(),"werewolf","实验训练时钟",RoomStatus.WAITING,9,false,null,"text",
                new LinkedHashMap<>(Map.of("playerCount",9,"template","standard","speechTime",60,"hasLastWords","none")));
        var seats=new ArrayList<RoomSeat>();
        for(int i=0;i<9;i++) seats.add(new RoomSeat(i,UUID.randomUUID().toString(),"席位"+i,true,"ai1","",true,false));
        room.setSeats(seats);room.setSource("LAB");room.syncSeatCount();
        room.getPrivateConfig().put("labSeed",17L);room.getPrivateConfig().put("labRoleSeed",71L);
        room.getPrivateConfig().put("lab",Map.of("experimentId","test","matchId",room.getId(),"labRunMode",mode));
        room=rooms.saveAndFlush(room);runtime.startLab(room.getId());return room;
    }
    private AiTurnJob claim(Room room) {
        var queued=jobs.findByRoomIdAndStatusIn(room.getId(),List.of("QUEUED")).getFirst();
        var job=coordinator.claim(queued.getId());assertNotNull(job);assertTrue(runtime.beginWerewolfTurn(job.getId()));return job;
    }
    private AiTurnDecision skip() {return new AiTurnDecision(action("SKIP","",null),"",Map.of(),List.of(),Map.of(),false,Map.of());}

    @Test void trainingPendingRecoveryPreservesTheSameActionAndItsRemainingTime() {
        var room=room("TRAINING");var job=claim(room);
        assertTrue(runtime.remainingTurnMillis(job.getId())>140_000);
        var pending=Map.<String,Object>of("nextAttempt",0,"calls",1,"lastCallCode","AI_RESULT_PENDING","lastCallState",Map.of("remoteState","RUNNING"));
        runtime.defer(job.getId(),pending,"AI_RESULT_PENDING");
        var state=states.findById(room.getId()).orElseThrow();
        long remaining=WerewolfAiClock.remainingExecutionMillis(state,LocalDateTime.now());
        assertTrue(WerewolfAiClock.waitingForOriginal(state));
        state.setPhaseEndsAt(LocalDateTime.now().minusMinutes(2));states.saveAndFlush(state);
        runtime.tick(room.getId());
        var resumed=claim(room);assertEquals(job.getId(),resumed.getId());
        assertEquals(remaining,runtime.remainingTurnMillis(job.getId()));
        runtime.complete(job.getId(),skip());
        assertEquals("SUCCEEDED",jobs.findById(job.getId()).orElseThrow().getStatus());
        assertFalse(states.findById(room.getId()).orElseThrow().getData().containsKey("labCallPause"));
        verifyNoInteractions(client);
    }

    @Test void restartAfterOldDeadlineQueriesTheOriginalInsteadOfDiscardingTheOpportunity() {
        var room=room("TRAINING");var job=claim(room);
        runtime.checkpoint(job.getId(),Map.of("nextAttempt",0,"calls",1));
        var state=states.findById(room.getId()).orElseThrow();
        var clock=map(state.getData().get("werewolfAiClock"));clock.put("executionEndsAt",LocalDateTime.now().minusMinutes(2).toString());
        state.getData().put("werewolfAiClock",clock);states.saveAndFlush(state);
        runtime.recoverLabJob(job.getId());
        var resumed=claim(room);assertEquals(job.getId(),resumed.getId());
        assertEquals(1,runtime.remainingTurnMillis(job.getId()));
        assertEquals("AI_RESULT_PENDING",map(resumed.getObservation().get("generationCheckpoint")).get("lastCallCode"));
        runtime.complete(job.getId(),skip());
        assertEquals("SUCCEEDED",jobs.findById(job.getId()).orElseThrow().getStatus());
        verifyNoInteractions(client);
    }

    @Test void correctionAfterRecoveredReplyResumesOnlyTheRemainingExecutionBudget() {
        var room=room("TRAINING");var job=claim(room);
        runtime.defer(job.getId(),Map.of("nextAttempt",0,"calls",1,"lastCallCode","AI_RESULT_PENDING","lastCallState",Map.of("remoteState","RUNNING")),"AI_RESULT_PENDING");
        claim(room);
        runtime.checkpoint(job.getId(),Map.of("nextAttempt",1,"calls",2,"actionExecutionRemainingMs",40_000));
        var state=states.findById(room.getId()).orElseThrow();
        assertFalse(WerewolfAiClock.waitingForOriginal(state));assertTrue(WerewolfAiClock.running(state));
        assertTrue(runtime.remainingTurnMillis(job.getId())<=40_000);assertTrue(runtime.remainingTurnMillis(job.getId())>38_000);
        runtime.defer(job.getId(),Map.of("nextAttempt",1,"calls",2,"lastCallCode","AI_RESULT_PENDING","lastCallState",Map.of("remoteState","RUNNING")),"AI_RESULT_PENDING");
        state=states.findById(room.getId()).orElseThrow();
        assertTrue(WerewolfAiClock.remainingExecutionMillis(state,LocalDateTime.now())<=40_000);
        verifyNoInteractions(client);
    }

    @Test void budgetPauseAndReloadRetainTheTrainingExecutionWindow() {
        var room=room("TRAINING");var job=claim(room);
        runtime.defer(job.getId(),Map.of("nextAttempt",0),LabBudgetService.EXHAUSTED);
        var state=states.findById(room.getId()).orElseThrow();
        assertTrue(state.getData().containsKey("labBudgetClock"));
        long remaining=WerewolfAiClock.remainingExecutionMillis(state,LocalDateTime.now());
        assertNull(coordinator.claim(job.getId()));
        runtime.resumeLabBudget(room.getId());
        var resumed=claim(room);assertEquals(job.getId(),resumed.getId());
        assertTrue(runtime.remainingTurnMillis(job.getId())<=remaining);
        assertTrue(runtime.remainingTurnMillis(job.getId())>remaining-2000);
        runtime.complete(job.getId(),skip());
        assertEquals("SUCCEEDED",jobs.findById(job.getId()).orElseThrow().getStatus());
        verifyNoInteractions(client);
    }

    @Test void trainingTimeoutPausesWithoutArchivingOrSkippingAndEvaluationKeeps50Seconds() {
        var room=room("TRAINING");var job=claim(room);
        var state=states.findById(room.getId()).orElseThrow();var phase=state.getData().get("phaseSerial");
        var clock=map(state.getData().get("werewolfAiClock"));clock.put("executionEndsAt",LocalDateTime.now().minusSeconds(1).toString());
        state.getData().put("werewolfAiClock",clock);states.saveAndFlush(state);runtime.tick(room.getId());runtime.tick(room.getId());
        state=states.findById(room.getId()).orElseThrow();
        assertEquals("TIMEOUT",state.getData().get("labCallPause"));assertEquals(phase,state.getData().get("phaseSerial"));
        assertNotEquals("SETTLEMENT",state.getPhase());assertTrue(state.getData().containsKey("labBudgetClock"));
        assertEquals("RUNNING",jobs.findById(job.getId()).orElseThrow().getStatus());
        var evaluation=room("EVALUATION");var evaluationJob=claim(evaluation);
        assertTrue(runtime.remainingTurnMillis(evaluationJob.getId())>48_000);assertTrue(runtime.remainingTurnMillis(evaluationJob.getId())<=50_000);
        verifyNoInteractions(client);
    }

    @Test void evaluationKnownFormatFailureSkipsAndCountsFailureInsteadOfPausingTheGame() {
        var room=room("EVALUATION");var job=claim(room);
        runtime.checkpoint(job.getId(),Map.of("calls",1,"originalRequestId",job.getId()+":0"));
        runtime.fail(job.getId(),"REQUEST_FAILED",Map.of("calls",1,"responsesObserved",1,"usageComplete",true,"qualityFlags",List.of("INVALID_JSON_ENVELOPE")));
        assertEquals("FAILED",jobs.findById(job.getId()).orElseThrow().getStatus());
        var state=states.findById(room.getId()).orElseThrow();assertFalse(state.getData().containsKey("labCallPause"));
        assertFalse(state.getData().containsKey("labBudgetClock"));assertFalse(jobs.findByRoomIdAndStatusIn(room.getId(),List.of("QUEUED")).isEmpty());
        assertEquals(1,maps(state.getData().get("events")).stream().filter(event->"AI_ACTION_FAILED".equals(event.get("type"))).count());
        verifyNoInteractions(client);
    }

    @Test void evaluationConfirmedUnsentExhaustionSkipsWithoutPausing() {
        var room=room("EVALUATION");var job=claim(room);
        runtime.defer(job.getId(),Map.of("calls",0,"lastCallCode","AI_REQUEST_NOT_SENT","transportAttempt",4),"LAB_CALL_RETRIES_EXHAUSTED");
        assertEquals("FAILED",jobs.findById(job.getId()).orElseThrow().getStatus());
        assertFalse(states.findById(room.getId()).orElseThrow().getData().containsKey("labCallPause"));
        verifyNoInteractions(client);
    }

    @Test void evaluationUnknownAfterDeadlineWaitsForOriginalThenCountsRecoveredResultAsTimeoutExactlyOnce() {
        var room=room("EVALUATION");var job=claim(room);
        runtime.checkpoint(job.getId(),Map.of("calls",1,"originalRequestId",job.getId()+":0"));
        var state=states.findById(room.getId()).orElseThrow();var clock=map(state.getData().get("werewolfAiClock"));
        clock.put("executionEndsAt",LocalDateTime.now().minusSeconds(5).toString());state.getData().put("werewolfAiClock",clock);states.saveAndFlush(state);
        runtime.tick(room.getId());
        assertTrue(runtime.rules("werewolf").isTurnCurrent(states.findById(room.getId()).orElseThrow(),new com.aisocialgame.engine.v2.TurnRequest(job.getActorId(),job.getKind(),job.getTurnKey())));
        runtime.fail(job.getId(),"TIMEOUT",Map.of("calls",1,"responsesObserved",0,"usageComplete",false));
        assertEquals("QUEUED",jobs.findById(job.getId()).orElseThrow().getStatus());
        assertEquals(job.getId(),claim(room).getId());assertEquals(1,runtime.remainingTurnMillis(job.getId()));
        runtime.complete(job.getId(),skip());
        assertEquals("FAILED",jobs.findById(job.getId()).orElseThrow().getStatus());
        state=states.findById(room.getId()).orElseThrow();int events=maps(state.getData().get("events")).size();
        assertFalse(state.getData().containsKey("labCallPause"));
        assertEquals(1,maps(state.getData().get("events")).stream().filter(event->"AI_ACTION_FAILED".equals(event.get("type"))).count());
        runtime.complete(job.getId(),skip());assertEquals(events,maps(states.findById(room.getId()).orElseThrow().getData().get("events")).size());
        verifyNoInteractions(client);
    }
}
