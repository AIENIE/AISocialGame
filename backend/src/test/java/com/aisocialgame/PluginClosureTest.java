package com.aisocialgame;

import com.aisocialgame.dto.*;
import com.aisocialgame.engine.ValidationResult;
import com.aisocialgame.engine.v2.*;
import com.aisocialgame.integration.grpc.client.AiGrpcClient;
import com.aisocialgame.model.*;
import com.aisocialgame.repository.*;
import com.aisocialgame.service.*;
import com.aisocialgame.service.ai.v2.*;
import com.aisocialgame.service.v2.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;
import java.time.LocalDateTime;
import java.util.*;
import static com.aisocialgame.engine.v2.RuleSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** A fourth rule implementation lives only in tests and uses the unchanged orchestrator. */
@SpringBootTest(classes={AiSocialGameApplication.class, PluginClosureTest.Config.class})
@ActiveProfiles("test")
class PluginClosureTest {
    @TestConfiguration static class Config { @Bean GameRuleSet fourthPlugin() { return new MiniRule(); } }
    @Autowired GameService games;
    @Autowired RoomRepository rooms;
    @Autowired AiTurnJobRepository jobs;
    @Autowired V2GameService runtime;
    @Autowired AiTurnCoordinator coordinator;
    @Autowired GamePlayService facade;
    @Autowired ReplayArchiveService replays;
    @MockitoBean AiGrpcClient rpc;

    @Test void registeredPluginUsesMetadataObservationFallbackActionAndSettlementWithoutCentralBranches() {
        assertEquals(1, games.findById("test_fourth").orElseThrow().getMinPlayers());
        String id=UUID.randomUUID().toString();
        Room room=new Room(id,"test_fourth","test plugin",RoomStatus.WAITING,1,false,null,"text",new LinkedHashMap<>());
        room.setSeats(List.of(new RoomSeat(0,"bot","bot",true,"ai2","",true,true))); room.setHostUserId("bot");rooms.saveAndFlush(room);
        User host=new User();host.setId("bot");
        facade.start("test_fourth",id,host);
        var job=jobs.findAll().stream().filter(j->j.getRoomId().equals(id)).findFirst().orElseThrow();
        var claimed=coordinator.claim(job.getId());assertNotNull(claimed);
        var observation=new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules().convertValue(job.getObservation(),VisibleObservation.class);
        assertEquals("ai2",observation.persona().get("id"));assertFalse(observation.privateFacts().containsKey("otherSecret"));
        runtime.complete(job.getId(),AiTurnDecision.fallback(runtime.rules("test_fourth").fallback(observation)));
        var result=facade.state("test_fourth",id,host);assertEquals("SETTLEMENT",result.getPhase());
        assertEquals("SUCCEEDED",jobs.findById(job.getId()).orElseThrow().getStatus());
        assertFalse(replays.authorizedEvents(String.valueOf(result.getExtra().get("archiveId")),"PUBLIC",null,"logged-spectator").getEvents().isEmpty());
        org.mockito.Mockito.verifyNoInteractions(rpc);
    }
    static class MiniRule implements GameRuleSet {
        public String gameId(){return "test_fourth";}
        public ValidationResult validateStart(Room room){return ValidationResult.ok();}
        public GameState initialize(Room room,LocalDateTime now){var s=newState(room,now);s.setRoundNumber(1);phase(s,"TEST_TURN",0,60,now);return s;}
        public void apply(GameState state,String actor,PlayerAction action,LocalDateTime now){require("SKIP".equals(action.getType()),"invalid");event(state,"TEST_DONE",actor,null,"test complete",Map.of());state.getData().put("winnerIds",List.of(actor));state.getData().put("winner","TEST");phase(state,"SETTLEMENT",null,0,now);}
        public void advance(GameState state,LocalDateTime now){}
        public List<LegalAction> legalActions(GameState s,String actor){return "TEST_TURN".equals(s.getPhase())?List.of(LegalAction.simple("SKIP","skip")):List.of();}
        public List<TurnRequest> pendingTurns(GameState s){return "TEST_TURN".equals(s.getPhase())?List.of(turn(s,"bot","TEST_TURN")):List.of();}
        public Map<String,Object> publicData(GameState s){return Map.of();}
        public Map<String,Object> privateData(GameState s,String actor){return Map.of();}
        public String instruction(VisibleObservation o){return "Choose the legal skip action.";}
        public PlayerAction fallback(VisibleObservation o){return action("SKIP",null,null);}
    }
}
