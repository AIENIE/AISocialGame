package com.aisocialgame;

import com.aisocialgame.engine.v2.*;
import com.aisocialgame.model.*;
import com.aisocialgame.repository.*;
import com.aisocialgame.service.ai.v2.*;
import com.aisocialgame.service.v2.*;
import com.aisocialgame.integration.grpc.client.AiGrpcClient;
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

@SpringBootTest(classes=AiSocialGameApplication.class) @ActiveProfiles("test")
class WerewolfFlowIntegrationTest {
    @Autowired V2GameService runtime;
    @Autowired AiTurnCoordinator coordinator;
    @Autowired RoomRepository rooms;
    @Autowired GameStateRepository states;
    @Autowired AiTurnJobRepository jobs;
    @Autowired GameArchiveRepository archives;
    @MockitoBean AiGrpcClient client;
    @MockitoBean AiValidationBudgetService budgets;

    private Room room() {
        var room=new Room(UUID.randomUUID().toString(),"werewolf","狼人流程测试",RoomStatus.WAITING,6,false,null,"text",
                new LinkedHashMap<>(Map.of("playerCount",6,"template","standard","speechTime",60,"hasLastWords","none")));
        var seats=new ArrayList<RoomSeat>();
        for(int i=0;i<6;i++) seats.add(new RoomSeat(i,UUID.randomUUID().toString(),"席位"+i,true,"ai1","",true,i==0));
        room.setSeats(seats); room.setHostUserId(seats.getFirst().getPlayerId()); room.syncSeatCount(); return rooms.saveAndFlush(room);
    }
    private User host(Room room) {var u=new User();u.setId(room.getHostUserId());return u;}
    private AiTurnJob queued(Room room) {return jobs.findByRoomIdAndStatusIn(room.getId(),List.of("QUEUED")).getFirst();}
    private AiTurnJob claim(Room room) {
        var job=coordinator.claim(queued(room).getId()); assertNotNull(job); assertTrue(runtime.beginWerewolfTurn(job.getId()));return job;
    }
    private AiTurnDecision skip() {return new AiTurnDecision(action("SKIP","",null),"",Map.of(),List.of(),Map.of(),false,Map.of());}

    @Test void staleRunningNightJobDoesNotConsumeTheNextRoleWindowOrSubmitLate() {
        var room=room();runtime.start("werewolf",room.getId(),host(room));var old=claim(room);
        var state=states.findById(room.getId()).orElseThrow();
        var clock=map(state.getData().get("werewolfAiClock"));clock.put("executionEndsAt",LocalDateTime.now().minusSeconds(1).toString());
        state.getData().put("werewolfAiClock",clock);states.saveAndFlush(state);runtime.tick(room.getId());
        var next=queued(room); assertNotEquals(old.getId(),next.getId());assertEquals("DISCARDED",jobs.findById(old.getId()).orElseThrow().getStatus());
        var before=states.findById(room.getId()).orElseThrow();var serial=before.getData().get("phaseSerial");
        runtime.complete(old.getId(),skip());
        assertEquals(serial,states.findById(room.getId()).orElseThrow().getData().get("phaseSerial"));
        assertTrue(runtime.beginWerewolfTurn(coordinator.claim(next.getId()).getId()));
        assertTrue(runtime.remainingTurnMillis(next.getId())>48_000);
        verifyNoInteractions(client);
    }

    @Test void restartKeepsOriginalRequestCheckpointAndExecutionDeadline() {
        var room=room();runtime.start("werewolf",room.getId(),host(room));var original=claim(room);
        var deadline=jobs.findById(original.getId()).orElseThrow().getDiagnostics().get("executionDeadlineEpochMs");
        runtime.checkpoint(original.getId(),Map.of("nextAttempt",0,"transportAttempt",0,"calls",0));
        runtime.recoverLabJob(original.getId());
        var restored=coordinator.claim(original.getId());assertNotNull(restored);assertTrue(runtime.beginWerewolfTurn(restored.getId()));
        assertEquals(deadline,jobs.findById(restored.getId()).orElseThrow().getDiagnostics().get("executionDeadlineEpochMs"));
        assertEquals(0,map(restored.getObservation().get("generationCheckpoint")).get("nextAttempt"));
        runtime.complete(restored.getId(),skip());
        assertEquals("SUCCEEDED",jobs.findById(restored.getId()).orElseThrow().getStatus());
        verifyNoInteractions(client);
    }

    @Test void budgetPauseRetainsAnInFlightValidResultAndStartsNoNewCall() {
        var room=room();runtime.start("werewolf",room.getId(),host(room));var job=claim(room);
        when(budgets.exhausted(room.getId())).thenReturn(true);runtime.tick(room.getId());
        assertTrue(states.findById(room.getId()).orElseThrow().getData().containsKey("validationBudgetPause"));
        runtime.complete(job.getId(),skip());
        assertEquals("SUCCEEDED",jobs.findById(job.getId()).orElseThrow().getStatus());
        assertTrue(jobs.findByRoomIdAndStatusIn(room.getId(),List.of("QUEUED","RUNNING")).isEmpty());
        var view=runtime.state("werewolf",room.getId(),host(room));
        assertEquals(true,view.getExtra().get("validationBudgetPaused"));assertEquals(List.of(),view.getExtra().get("legalActions"));
        verifyNoInteractions(client);
    }

    @Test void resumingBudgetPauseKeepsTheRemainingExecutionWindow() {
        var room=room();runtime.start("werewolf",room.getId(),host(room));var original=claim(room);
        when(budgets.exhausted(room.getId())).thenReturn(true);
        runtime.defer(original.getId(),Map.of("nextAttempt",0),AiValidationBudgetService.EXHAUSTED);
        var state=states.findById(room.getId()).orElseThrow();
        var pause=map(state.getData().get("validationBudgetPause"));
        pause.put("pausedAt",LocalDateTime.now().minusSeconds(120).toString());
        state.getData().put("validationBudgetPause",pause);
        var clock=map(state.getData().get("werewolfAiClock"));
        clock.put("executionEndsAt",LocalDateTime.now().minusSeconds(75).toString());
        state.getData().put("werewolfAiClock",clock);states.saveAndFlush(state);
        var old=jobs.findById(original.getId()).orElseThrow();
        old.getDiagnostics().put("executionDeadlineEpochMs",System.currentTimeMillis()-75_000);jobs.saveAndFlush(old);

        when(budgets.exhausted(room.getId())).thenReturn(false);runtime.tick(room.getId());
        var resumed=coordinator.claim(original.getId());assertNotNull(resumed);
        assertTrue(runtime.beginWerewolfTurn(resumed.getId()));
        assertTrue(runtime.remainingTurnMillis(resumed.getId())>43_000);
        assertTrue(runtime.remainingTurnMillis(resumed.getId())<=46_000);
        runtime.complete(resumed.getId(),skip());
        assertEquals("SUCCEEDED",jobs.findById(resumed.getId()).orElseThrow().getStatus());
        verifyNoInteractions(client);
    }

    @Test void sameRoomCompletesThreeGamesWithIndependentArchivesAndFreshSkills() {
        var room=room();var ids=new HashSet<String>();var seeds=new HashSet<Object>();
        for(int game=0;game<3;game++) {
            runtime.start("werewolf",room.getId(),host(room));
            var initial=states.findById(room.getId()).orElseThrow();
            String archive=text(initial.getData().get("archiveId"));assertTrue(ids.add(archive));assertTrue(seeds.add(initial.getData().get("randomSeed")));
            var facts=map(initial.getData().get("werewolf"));assertEquals(false,facts.get("antidoteUsed"));assertEquals(false,facts.get("poisonUsed"));assertTrue(map(facts.get("seerChecks")).isEmpty());
            for(int step=0;step<180;step++) {
                var state=states.findById(room.getId()).orElseThrow();if("SETTLEMENT".equals(state.getPhase()))break;
                runtime.tick(room.getId());var job=claim(room);
                var selected=action("SKIP","",null);
                if("DAY_VOTE".equals(state.getPhase())) {
                    var wolf=state.getPlayers().stream().filter(p->p.isAlive()&&"WEREWOLF".equals(p.getRole())&&!p.getPlayerId().equals(job.getActorId())).findFirst();
                    if(wolf.isPresent()) selected=action("VOTE","依据本轮公开发言。",wolf.get().getPlayerId());
                }
                runtime.complete(job.getId(),new AiTurnDecision(selected,"",Map.of(),List.of(),Map.of(),false,Map.of()));
            }
            var result=states.findById(room.getId()).orElseThrow();assertEquals("SETTLEMENT",result.getPhase());assertEquals(RoomStatus.WAITING,rooms.findById(room.getId()).orElseThrow().getStatus());
            assertTrue(archives.existsById(archive));assertTrue(jobs.findByRoomIdAndStatusIn(room.getId(),List.of("QUEUED","RUNNING")).isEmpty());
        }
        assertEquals(3,ids.size());verifyNoInteractions(client);
    }
}
