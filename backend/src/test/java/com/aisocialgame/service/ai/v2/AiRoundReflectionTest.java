package com.aisocialgame.service.ai.v2;
import com.aisocialgame.model.GameState;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.aisocialgame.engine.v2.RuleSupport.*;
class AiRoundReflectionTest {
    @Test void reflectionPreservesReversalWithoutInferringSecretTruth() {
        GameState state=new GameState("r","undercover","VOTING"); state.setRoundNumber(2);
        var before=Map.<String,Object>of("beliefs",Map.of("p2",Map.of("hypothesis","可疑","evidenceEventIds",List.of("e1"))));
        var memory=new LinkedHashMap<String,Object>();memory.put("beliefs",Map.of("p2",Map.of("hypothesis","反证减弱怀疑","evidenceEventIds",List.of("e2"))));
        memory.put("commitments",List.of(Map.of("id","c1","status","NOT_FULFILLED")));
        var observation=new VisibleObservation("undercover","i","VOTING",1,"p1","VOTE",Map.of(),List.of(),Map.of(),List.of(),Map.of(),List.of(),Map.of(),before,List.of());
        AiRoundReflection.decision(memory,before,observation);
        state.getData().put("aiMemoriesV2",Map.of("p1",memory));state.getData().put("secretTruth","must never enter reflection");
        AiRoundReflection.closeRounds(state);
        var saved=map(map(state.getData().get("aiMemoriesV2")).get("p1"));var reflection=maps(saved.get("roundReflections")).getFirst();
        assertEquals("UNKNOWN",reflection.get("truthAssessment"));assertEquals(1,maps(reflection.get("judgmentChanges")).size());
        assertEquals(List.of("c1"),reflection.get("confirmedRecordIssues"));assertFalse(saved.toString().contains("secretTruth"));
        AiRoundReflection.closeRounds(state);assertEquals(1,maps(map(map(state.getData().get("aiMemoriesV2")).get("p1")).get("roundReflections")).size());
    }
    @Test void difficultyGuidesAreDistinctAndDoNotChangeCapabilities() {
        assertNotEquals(AiRoundReflection.difficulty(1).get("guide"),AiRoundReflection.difficulty(3).get("guide"));
        assertEquals(2,AiRoundReflection.difficulty(99).get("level"));
    }
    @Test void changingRoomDifficultyKeepsPersonaAndVisibilityIdentical() {
        var rule=new com.aisocialgame.engine.v2.undercover.UndercoverRuleSet(new com.aisocialgame.engine.v2.undercover.UndercoverWordCatalog());
        var room=new com.aisocialgame.model.Room("difficulty","undercover","test",com.aisocialgame.model.RoomStatus.WAITING,4,false,null,"text",new LinkedHashMap<>(Map.of("aiDifficulty",1)));
        var seats=new ArrayList<com.aisocialgame.model.RoomSeat>();for(int i=0;i<4;i++)seats.add(new com.aisocialgame.model.RoomSeat(i,"p"+i,"player"+i,true,"ai1","",true,i==0));room.setSeats(seats);
        var state=rule.initialize(room,java.time.LocalDateTime.now());var personas=new com.aisocialgame.repository.PersonaRepository();
        var factory=new ObservationFactory(personas,new AiMemoryServiceV2(org.mockito.Mockito.mock(com.aisocialgame.repository.AiPersonaMemoryRepository.class),personas));
        var turn=rule.pendingTurns(state).getFirst();var simple=factory.build(state,rule,turn);
        var configuration=map(state.getData().get("rules"));configuration.put("aiDifficulty",3);state.getData().put("rules",configuration);var advanced=factory.build(state,rule,turn);
        assertEquals(1,map(simple.persona().get("difficulty")).get("level"));assertEquals(3,map(advanced.persona().get("difficulty")).get("level"));
        assertEquals(simple.persona().get("behaviorGuide"),advanced.persona().get("behaviorGuide"));assertEquals(simple.privateFacts(),advanced.privateFacts());assertEquals(simple.legalActions(),advanced.legalActions());
    }

}
