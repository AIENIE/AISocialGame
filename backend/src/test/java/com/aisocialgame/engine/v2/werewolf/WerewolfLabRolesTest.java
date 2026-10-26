package com.aisocialgame.engine.v2.werewolf;

import com.aisocialgame.model.*;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WerewolfLabRolesTest {
    private final WerewolfRuleSet rules=new WerewolfRuleSet();
    private Room room(long seed, boolean lab) {
        var room=new Room("roles","werewolf","roles",RoomStatus.WAITING,9,false,null,"text",
                Map.of("playerCount",9,"template","standard","speechTime",60));
        var seats=new ArrayList<RoomSeat>();
        for(int i=0;i<9;i++) seats.add(new RoomSeat(i,"p"+i,"玩家"+i,true,"ai1","",true,false));
        Collections.shuffle(seats,new Random(seed));
        for(int i=0;i<9;i++) seats.get(i).setSeatNumber(i);
        room.setSeats(seats);
        if(lab) {room.setSource("LAB");room.getPrivateConfig().put("labSeed",seed);}
        return room;
    }
    @Test void independentRoleSeedRotatesEachParticipantInsteadOfBindingRosterOrderToRoles() {
        var observed=new LinkedHashMap<String,Set<String>>();
        for(int seed=0;seed<40;seed++) {
            var room=room(seed,true);room.getPrivateConfig().put("labRoleSeed",seed*7919L+104729);
            var state=rules.initialize(room,LocalDateTime.of(2030,1,1,0,0));
            state.getPlayers().forEach(p->observed.computeIfAbsent(p.getPlayerId(),id->new HashSet<>()).add(p.getRole()));
            assertFalse(rules.publicData(state).toString().contains("labRoleSeed"));
        }
        assertEquals(9,observed.size());
        assertTrue(observed.values().stream().allMatch(roles->roles.size()>=3),observed.toString());
    }
    @Test void exactEvaluationAssignmentsAreValidatedAndIndependentOfSeatOrder() {
        var assigned=Map.of("p0","WEREWOLF","p1","WEREWOLF","p2","WEREWOLF","p3","SEER","p4","WITCH","p5","HUNTER","p6","VILLAGER","p7","VILLAGER","p8","VILLAGER");
        for(long seed:new long[]{1,72}) {
            var room=room(seed,true);room.getPrivateConfig().put("labRoleAssignments",assigned);
            var state=rules.initialize(room,LocalDateTime.of(2030,1,1,0,0));
            state.getPlayers().forEach(p->assertEquals(assigned.get(p.getPlayerId()),p.getRole()));
        }
        var invalid=room(1,true);invalid.getPrivateConfig().put("labRoleAssignments",Map.of("p0","WEREWOLF"));
        assertThrows(com.aisocialgame.exception.ApiException.class,()->rules.initialize(invalid,LocalDateTime.now()));
    }
}
