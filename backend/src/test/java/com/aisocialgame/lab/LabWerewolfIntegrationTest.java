package com.aisocialgame.lab;

import com.aisocialgame.AiSocialGameApplication;
import com.aisocialgame.model.*;
import com.aisocialgame.repository.*;
import com.aisocialgame.service.ai.config.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static com.aisocialgame.engine.v2.RuleSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(classes=AiSocialGameApplication.class)
@ActiveProfiles("test")
@Transactional
class LabWerewolfIntegrationTest {
    @Autowired LabService service;
    @Autowired LabRuntime runtime;
    @Autowired LabExperimentRepository experiments;
    @Autowired LabMatchRepository matches;
    @Autowired LabEvaluationRepository evaluations;
    @Autowired RoomRepository rooms;
    @Autowired GameStateRepository states;
    @MockitoBean AiModelConfigService models;
    @MockitoBean LabStrategyService strategies;
    final LabRequests.Selection selection=new LabRequests.Selection("fixture",AiModelSelection.ThinkingMode.ENABLED,"LOW");
    String draft() {
        var ids=new ArrayList<String>();
        for(int i=0;i<4;i++) ids.add(service.saveAgent(null,new LabRequests.Agent("fixture "+i,"ai"+(i+1),selection)).id);
        var participants=new ArrayList<LabRequests.Participant>();for(int i=0;i<9;i++) participants.add(new LabRequests.Participant(ids.get(i%4),null,selection));
        return text(service.create(new LabRequests.Experiment("werewolf fixture",9,2,null,false,3000,selection,participants,
                "werewolf",Map.of(),"SHARED_STRATEGY",null,20261026L,900L),"fixture-admin").get("id"));
    }
    LabMatch next(String id,int sequence) {runtime.advance(id);runtime.advance(id);return matches.findByExperimentIdAndSequenceNumber(id,sequence).orElseThrow();}
    @Test void createFreezesTwentyIndependentSituationsAndActualGameHasSeparatePrivateSeeds() {
        String id=draft();var experiment=experiments.findById(id).orElseThrow();
        assertEquals(20,maps(experiment.configuration.get("scenarioManifest")).size());assertFalse(experiment.configuration.containsKey("strategyHeadId"));
        service.control(id,"start");var match=next(id,1);var room=rooms.findById(match.roomId).orElseThrow();
        assertEquals("werewolf",room.getGameId());assertEquals("PLAYING",match.status);assertEquals(9,map(match.snapshot.get("roleAssignments")).size());
        assertNotEquals(room.getPrivateConfig().get("labSeatSeed"),room.getPrivateConfig().get("labRoleSeed"));
        assertFalse(room.getConfig().containsKey("labRoleSeed"));assertFalse(room.getConfig().containsKey("labRoleAssignments"));
        assertEquals("TRAINING",map(room.getPrivateConfig().get("lab")).get("labRunMode"));
        assertEquals(3,map(match.snapshot.get("roleAssignments")).values().stream().filter("WEREWOLF"::equals).count());
    }
    @Test void pairedGamesKeepActualSeatAndRoleAssignmentsAndOnlyTargetGetsCandidate() {
        String id=draft();var experiment=experiments.findById(id).orElseThrow();experiment.status="PAUSED";experiments.saveAndFlush(experiment);
        var candidate=Map.<String,Object>of("id","candidate","experimentId",id,"content",Map.of("cards",List.of()));when(strategies.strategySnapshot("candidate")).thenReturn(candidate);
        String target=LabService.participantId(maps(experiment.configuration.get("roster")).getFirst());
        var request=new LabRequests.Evaluation(target,1,null,"candidate","paired-operation","WITCH",202610261L);
        var evaluation=service.evaluate(id,request);assertEquals(evaluation.get("id"),service.evaluate(id,request).get("id"));
        var row=evaluations.findById(text(evaluation.get("id"))).orElseThrow();row.configuration.put("singleAttempt",true);evaluations.saveAndFlush(row);
        var first=next(id,1);var state=states.findById(first.roomId).orElseThrow();state.setPhase("SETTLEMENT");states.saveAndFlush(state);
        runtime.advance(id);runtime.advance(id);var second=matches.findByExperimentIdAndSequenceNumber(id,2).orElseThrow();
        if(!"PLAYING".equals(second.status)) {runtime.advance(id);second=matches.findById(second.id).orElseThrow();}
        assertEquals(first.snapshot.get("seatOrder"),second.snapshot.get("seatOrder"));assertEquals(first.snapshot.get("roleAssignments"),second.snapshot.get("roleAssignments"));
        assertEquals("WITCH",map(first.snapshot.get("roleAssignments")).get("lab-"+target));
        for(var match:List.of(first,second)) for(var entry:map(match.snapshot.get("players")).entrySet()) {
            var snapshot=map(map(entry.getValue()).get("strategySnapshot"));
            assertEquals("CANDIDATE".equals(match.variant) && entry.getKey().equals("lab-"+target),!snapshot.isEmpty());
        }
        assertEquals(Set.of("BASELINE","CANDIDATE"),Set.of(first.variant,second.variant));assertFalse(experiment.configuration.containsKey("strategyHeadId"));
        for(var match:List.of(first,second)) {
            var room=rooms.findById(match.roomId).orElseThrow();assertEquals(true,map(room.getPrivateConfig().get("lab")).get("scenarioEvaluation"));
            assertFalse(room.getConfig().containsKey("scenarioEvaluation"));
            assertEquals(50_000,LabRunPolicy.decisionMillis(states.findById(match.roomId).orElseThrow()));
        }
    }
    @Test void publicTrialMarkerIsFrozenWhiteListWithoutRoleOrEvidenceSignals() {
        var state=new GameState();state.setGameId("werewolf");
        state.getData().put(LabStrategyService.STATE_SNAPSHOTS,Map.of("actor",Map.of("source","PUBLISHED","compatible",true,
                "strategySnapshot",Map.of("id","v1","sequenceNumber",1,"content",Map.of("private","never public")))));
        var shown=StrategyPublicationService.publicTrialMetadata(state);assertEquals(Map.of("mode","TRIAL","strategyVersionId","v1","sequenceNumber",1),shown.get("actor"));
        state.setPhase("NIGHT");assertEquals(shown,StrategyPublicationService.publicTrialMetadata(state));
        state.getData().put("lab",Map.of("experimentId","lab"));assertTrue(StrategyPublicationService.publicTrialMetadata(state).isEmpty());
    }
}
