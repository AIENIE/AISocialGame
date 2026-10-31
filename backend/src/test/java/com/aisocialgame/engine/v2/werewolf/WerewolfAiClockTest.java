package com.aisocialgame.engine.v2.werewolf;

import com.aisocialgame.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.LocalDateTime;
import java.util.*;
import static com.aisocialgame.engine.v2.RuleSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class WerewolfAiClockTest {
    private final WerewolfRuleSet rules = new WerewolfRuleSet();
    private final LocalDateTime start = LocalDateTime.of(2030,1,1,12,0);

    private GameState game(int count) {
        var room = new Room("clock-room","werewolf","clock",RoomStatus.WAITING,count,false,null,"text",
                Map.of("playerCount",count,"template","standard","hasLastWords","none","speechTime",60));
        var seats = new ArrayList<RoomSeat>();
        for(int i=0;i<count;i++) seats.add(new RoomSeat(i,"p"+i,"玩家"+i,false,"ai1","",true,i==0));
        room.setSeats(seats); return rules.initialize(room,start);
    }
    private LocalDateTime voting(GameState state) {
        var now=start;
        for(int i=0;i<100 && !"DAY_VOTE".equals(state.getPhase());i++) {
            if("DAY_INTERACTION".equals(state.getPhase())) { now=state.getPhaseEndsAt(); rules.advance(state,now); }
            else {
                var actor=state.getPlayers().stream().filter(p -> !rules.legalActions(state,p.getPlayerId()).isEmpty()).findFirst().orElseThrow();
                rules.apply(state,actor.getPlayerId(),action("SKIP","",null),now);
            }
        }
        assertEquals("DAY_VOTE",state.getPhase()); return now;
    }

    @ParameterizedTest @ValueSource(ints={6,9,12})
    void slowSerialAiVotesReceiveIndividualWindowsAndHumanDeadlineStillCloses(int count) throws Exception {
        GameState state=game(count); LocalDateTime now=voting(state);
        for(int i=1;i<count;i++) state.getPlayers().get(i).setAi(true);
        LocalDateTime humanEnd=state.getPhaseEndsAt();
        assertTrue(WerewolfAiClock.synchronize(state,rules,now));
        var first=rules.pendingTurns(state).getFirst();
        assertEquals(now.plusSeconds(70),WerewolfAiClock.begin(state,first,now.plusSeconds(20)));
        now=humanEnd.plusSeconds(1); rules.advance(state,now);
        assertEquals("DAY_VOTE",state.getPhase());
        assertEquals("abstain",map(map(state.getData().get("werewolf")).get("votes")).get("p0"));
        assertEquals(count-1,((Number)rules.publicData(state).get("pendingAiCount")).intValue());
        assertThrows(com.aisocialgame.exception.ApiException.class,()->rules.apply(state,"p0",action("VOTE","","p1"),humanEnd.plusSeconds(1)));
        // A persisted restart retains the original deadline, rather than granting another 50 seconds.
        var mapper=new ObjectMapper().findAndRegisterModules();
        var restored=mapper.readValue(mapper.writeValueAsString(state),GameState.class);
        assertEquals(WerewolfAiClock.deadline(state),WerewolfAiClock.begin(restored,first,now));
        for(int i=1;i<count;i++) {
            WerewolfAiClock.synchronize(state,rules,now);
            var turn=rules.pendingTurns(state).getFirst();
            WerewolfAiClock.begin(state,turn,now);
            now=now.plusSeconds(20);
            rules.apply(state,turn.actorId(),action("VOTE","依据公开发言投票。","p0"),now);
            rules.advance(state,now);
        }
        var revealed=maps(state.getData().get("events")).stream().filter(e->"VOTE_REVEALED".equals(e.get("type"))).toList();
        assertEquals(count,revealed.size());
        assertEquals(count-1,revealed.stream().filter(e->"p0".equals(e.get("targetId"))).count());
        assertNotEquals("DAY_VOTE",state.getPhase());
    }

    @Test void nightQueueAndExecutionAreSeparateAndQueueFailureIsPrivate() {
        var state=game(6); state.getPlayers().forEach(p->p.setAi(true));
        WerewolfAiClock.synchronize(state,rules,start);
        var turn=rules.pendingTurns(state).getFirst();
        rules.advance(state,start.plusSeconds(31));
        assertTrue(rules.isTurnCurrent(state,turn));
        assertEquals(start.plusSeconds(109),WerewolfAiClock.begin(state,turn,start.plusSeconds(59)));
        rules.advance(state,start.plusSeconds(108)); assertTrue(rules.isTurnCurrent(state,turn));
        rules.advance(state,start.plusSeconds(109)); assertFalse(rules.isTurnCurrent(state,turn));
        var failures=maps(state.getData().get("events")).stream().filter(e->"AI_ACTION_FAILED".equals(e.get("type"))).toList();
        assertEquals(1,failures.size()); assertEquals("PRIVATE",failures.getFirst().get("visibility"));
        WerewolfAiClock.synchronize(state,rules,start.plusSeconds(109));
        var next=rules.pendingTurns(state).getFirst();
        rules.advance(state,start.plusSeconds(169));
        assertFalse(rules.isTurnCurrent(state,next));
        var last=maps(state.getData().get("events")).stream().filter(e->"AI_ACTION_FAILED".equals(e.get("type"))).toList().getLast();
        assertEquals("QUEUE_TIMEOUT",map(last.get("data")).get("reason"));
    }

    @Test void hunterReceivesPrivateDeadline() {
        var state=game(9); var hunter=state.getPlayers().stream().filter(p->"HUNTER".equals(p.getRole())).findFirst().orElseThrow();
        var data=map(state.getData().get("werewolf"));data.put("deathActor",hunter.getPlayerId());state.getData().put("werewolf",data);
        hunter.setAlive(false); phase(state,"DEATH_ACTION",null,30,start);
        assertEquals(start.plusSeconds(30).toString(),rules.privateData(state,hunter.getPlayerId()).get("actionEndsAt"));
        for(var other:state.getPlayers()) if(other!=hunter) assertFalse(rules.privateData(state,other.getPlayerId()).containsKey("actionEndsAt"));
        assertFalse(rules.publicData(state).containsKey("actionEndsAt"));
    }

    private GameState training() {
        var state=game(9); state.getPlayers().forEach(p->p.setAi(true));
        state.getData().put("lab",Map.of("experimentId","test","labRunMode","TRAINING"));
        return state;
    }

    @Test void trainingQueueAndOriginalRecoverySurviveReloadWithoutSpendingExecutionTime() throws Exception {
        var state=training(); WerewolfAiClock.synchronize(state,rules,start);
        var turn=rules.pendingTurns(state).getFirst();
        rules.advance(state,start.plusSeconds(100));
        assertTrue(rules.isTurnCurrent(state,turn));
        assertEquals(start.plusSeconds(250),WerewolfAiClock.begin(state,turn,start.plusSeconds(100)));
        WerewolfAiClock.suspend(state,start.plusSeconds(200),true);
        var mapper=new ObjectMapper().findAndRegisterModules();
        var restored=mapper.readValue(mapper.writeValueAsString(state),GameState.class);
        assertEquals(50_000,WerewolfAiClock.remainingExecutionMillis(restored,start.plusSeconds(500)));
        assertTrue(WerewolfAiClock.waitingForOriginal(restored));
        assertEquals(start.plusSeconds(650),WerewolfAiClock.begin(restored,turn,start.plusSeconds(500)));
        rules.advance(restored,start.plusSeconds(500));
        assertTrue(rules.isTurnCurrent(restored,turn));
        rules.apply(restored,turn.actorId(),action("SKIP","",null),start.plusSeconds(501));
        assertFalse(rules.isTurnCurrent(restored,turn));
        assertTrue(maps(restored.getData().get("events")).stream().noneMatch(e->"AI_ACTION_FAILED".equals(e.get("type"))));
    }

    @Test void trainingTimeoutPreservesTheOpportunityInsteadOfInventingASkip() {
        var state=training(); WerewolfAiClock.synchronize(state,rules,start);
        var turn=rules.pendingTurns(state).getFirst();
        WerewolfAiClock.begin(state,turn,start);
        rules.advance(state,start.plusSeconds(150));
        assertEquals("TIMEOUT",state.getData().get("labCallPause"));
        assertTrue(rules.isTurnCurrent(state,turn));
        assertTrue(maps(state.getData().get("events")).stream().noneMatch(e->"AI_ACTION_FAILED".equals(e.get("type"))));
    }

    @Test void evaluationAndMixedLabsKeepDeploymentWindows() {
        for(boolean mixed:new boolean[]{false,true}) {
            var state=training();
            if(mixed) state.getPlayers().getLast().setAi(false);
            else state.getData().put("lab",Map.of("experimentId","test","labRunMode","EVALUATION"));
            WerewolfAiClock.synchronize(state,rules,start);
            var turn=rules.pendingTurns(state).getFirst();
            assertEquals(start.plusSeconds(50),WerewolfAiClock.begin(state,turn,start));
            rules.advance(state,start.plusSeconds(50));
            assertFalse(rules.isTurnCurrent(state,turn));
            assertFalse(state.getData().containsKey("labCallPause"));
        }
    }

    @Test void trainingNotSentWaitRetainsRemainingTimeAndBudgetPauseCanShiftTheClock() {
        var state=training(); WerewolfAiClock.synchronize(state,rules,start);
        var turn=rules.pendingTurns(state).getFirst(); WerewolfAiClock.begin(state,turn,start);
        WerewolfAiClock.suspend(state,start.plusSeconds(20),false);
        WerewolfAiClock.shift(state,300_000);
        assertEquals(start.plusSeconds(450),WerewolfAiClock.begin(state,turn,start.plusSeconds(320)));
        assertEquals(130_000,WerewolfAiClock.remainingExecutionMillis(state,start.plusSeconds(320)));
    }
}
