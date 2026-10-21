package com.aisocialgame.lab;

import com.aisocialgame.engine.v2.undercover.*;
import com.aisocialgame.model.*;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import static com.aisocialgame.engine.v2.RuleSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class LabGameSimulationTest {
    @Test void oneThousandSeededGamesAcrossThreeTablesFinishAndPairedConditionsRemainIdentical() throws Exception {
        try(var workers=Executors.newFixedThreadPool(3)) {
            var runs=new ArrayList<Future<Integer>>();
            for(int table=0;table<3;table++) {
                int lane=table;
                runs.add(workers.submit(() -> {
                    var rules=new UndercoverRuleSet(new UndercoverWordCatalog());
                    int finished=0;
                    for(int game=lane;game<1000;game+=3) {
                        var room=room(game);
                        var state=rules.initialize(room,LocalDateTime.now());
                        var paired=rules.initialize(room(game),LocalDateTime.now());
                        assertEquals(state.getData().get("wordPairId"),paired.getData().get("wordPairId"));
                        assertEquals(state.getPlayers().stream().map(GamePlayerState::getRole).toList(),paired.getPlayers().stream().map(GamePlayerState::getRole).toList());
                        int actions=0;
                        while(!"SETTLEMENT".equals(state.getPhase())) {
                            assertTrue(actions++<300,"bounded game "+game);
                            var turn=rules.pendingTurns(state).getFirst();
                            var legal=rules.legalActions(state,turn.actorId());
                            var speak=legal.stream().filter(option -> "SPEAK".equals(option.type())).findFirst();
                            if(game%10==0 && player(state,turn.actorId()).getRole().equals("UNDERCOVER")) {
                                rules.apply(state,turn.actorId(),action("SELF_DESTRUCT",text(state.getData().get("civilianWord")),null),LocalDateTime.now());
                            } else if(speak.isPresent()) rules.apply(state,turn.actorId(),action("SPEAK","它有一种常见的用途。",null),LocalDateTime.now());
                            else {
                                var vote=legal.stream().filter(option -> "VOTE".equals(option.type())).findFirst().orElseThrow();
                                rules.apply(state,turn.actorId(),action("VOTE","",vote.targets().getFirst()),LocalDateTime.now());
                            }
                            rules.advance(state,LocalDateTime.now());
                        }
                        assertFalse(rules.winningPlayerIds(state).isEmpty() && !"DRAW".equals(state.getData().get("winner")));
                        finished++;
                    }
                    return finished;
                }));
            }
            int completed=0;
            for(var run:runs) completed+=run.get(45,TimeUnit.SECONDS);
            assertEquals(1000,completed);
        }
    }
    private static Room room(long seed) {
        var room=new Room(UUID.randomUUID().toString(),"undercover","simulation",RoomStatus.WAITING,6,false,null,"text",
                new LinkedHashMap<>(Map.of("playerCount",6,"wordPack","daily","hasBlank",seed%2==0,"speakTime",90,"spyMode","auto")));
        room.setSource("LAB"); room.getPrivateConfig().put("labSeed",seed);
        room.getPrivateConfig().put("lab",Map.of("experimentId","fixture","matchId","fixture"));
        for(int seat=0;seat<6;seat++) room.getSeats().add(new RoomSeat(seat,"ai-"+seat,"AI "+seat,true,"fixture","",true,false));
        return room;
    }
}
