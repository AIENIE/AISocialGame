package com.aisocialgame.engine.v2;
import com.aisocialgame.engine.v2.turtlesoup.*;
import com.aisocialgame.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.*;
import static com.aisocialgame.engine.v2.RuleSupport.*;
import static org.junit.jupiter.api.Assertions.*;
class TurtleSoupRandomClosureTest {
    @Test void randomResolvesOnceAndRecoveryNeverChangesThePersistedCaseOrExposesTruth() throws Exception {
        var json=new ObjectMapper().findAndRegisterModules();var catalog=new TurtleSoupCaseCatalog(json);var rule=new TurtleSoupRuleSet(catalog);
        var room=new Room("random-test","turtle_soup","random",RoomStatus.WAITING,5,false,null,"text",new LinkedHashMap<>(Map.of("caseId","random","playerCount",5)));
        room.setSeats(List.of(new RoomSeat(0,"human","human",false,null,"",true,true)));
        assertTrue(GameConfiguration.validate(rule.definition(),room.getConfig()).valid());assertTrue(rule.validateStart(room).valid());
        var now=LocalDateTime.now();var state=rule.initialize(room,now);String selected=text(state.getData().get("caseId"));
        assertNotEquals("random",selected);assertEquals(catalog.require(selected).version(),state.getData().get("caseVersion"));
        var recovered=json.readValue(json.writeValueAsString(state),GameState.class);
        for(int i=0;i<10;i++) {rule.publicData(recovered);rule.privateData(recovered,"human");rule.advance(recovered,now.plusMinutes(i));}
        assertEquals(selected,recovered.getData().get("caseId"));assertEquals(state.getData().get("caseVersion"),recovered.getData().get("caseVersion"));
        assertFalse(rule.publicData(recovered).containsKey("hostTruth"));assertFalse(rule.privateData(recovered,"human").containsKey("hostTruth"));
        assertFalse(json.writeValueAsString(rule.definition()).contains(json.writeValueAsString(catalog.require(selected).hostTruth())));
    }
}
