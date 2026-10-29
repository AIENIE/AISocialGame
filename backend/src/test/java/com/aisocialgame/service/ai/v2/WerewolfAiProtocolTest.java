package com.aisocialgame.service.ai.v2;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.aisocialgame.engine.v2.RuleSupport.*;

class WerewolfAiProtocolTest {
    private VisibleObservation observation(String game) {
        return new VisibleObservation(game,"instance","NIGHT",1,"player-b","NIGHT_ACTION",
                Map.of("playerId","player-b","role","WITCH","seatNumber",1),
                List.of(Map.of("playerId","player-b","seatNumber",1),Map.of("playerId","player-a","seatNumber",0)),Map.of(),
                List.of(Map.of("eventId","event-uuid","actorId","player-a","message","引用 player-a 原话 E1")),Map.of(),List.of(),Map.of(),Map.of(),List.of());
    }
    @Test void typedReferencesRoundTripAndPreserveSpeechAndRawOutput() {
        var o=observation("werewolf");var refs=WerewolfAiProtocol.references(o);
        assertEquals(Map.of("P1","player-a","P2","player-b"),refs.players());
        var raw=map(Map.of("action",Map.of("type","NIGHT_ACTION","nightAction","WITCH_SAVE","targetPlayerId","P2","content","我选择 P2，引用 E1"),
                "evidenceEventIds",List.of("E1"),"memoryUpdates",Map.of("beliefs",List.of(Map.of("playerId","P1","hypothesis","P1 是假设","evidenceEventIds",List.of("E1"))))));
        var decoded=WerewolfAiProtocol.decode(o,raw,WerewolfAiProtocol.restore(refs.snapshot()));
        assertEquals("player-b",map(decoded.output().get("action")).get("targetPlayerId"));
        assertEquals(true,map(decoded.output().get("action")).get("useHeal"));assertFalse(map(raw.get("action")).containsKey("useHeal"));
        assertEquals("我选择 P2，引用 E1",map(decoded.output().get("action")).get("content"));
        assertEquals(List.of("event-uuid"),decoded.output().get("evidenceEventIds"));
        assertEquals("player-a",maps(map(decoded.output().get("memoryUpdates")).get("beliefs")).getFirst().get("playerId"));
        assertEquals(List.of("WITCH_SAVE_MISSING_USE_HEAL_TRUE"),decoded.normalizations());
        var encoded=WerewolfAiProtocol.encode(Map.of("actorId","player-b","events",o.events()),refs);
        assertEquals("P2",encoded.get("actorId"));assertEquals("E1",maps(encoded.get("events")).getFirst().get("eventId"));
        assertEquals("引用 player-a 原话 E1",maps(encoded.get("events")).getFirst().get("message"));
    }
    @Test void wrongNamespacesAndUnknownAliasesFailWithoutGuessingOrTruncating() {
        var o=observation("werewolf");var refs=WerewolfAiProtocol.references(o);
        for(String target:List.of("E1","P99"))assertThrows(WerewolfAiProtocol.InvalidReference.class,()->WerewolfAiProtocol.decode(o,Map.of("action",Map.of("targetPlayerId",target)),refs));
        for(String event:List.of("P1","E99"))assertThrows(WerewolfAiProtocol.InvalidReference.class,()->WerewolfAiProtocol.decode(o,Map.of("evidenceEventIds",List.of(event)),refs));
        var full=Map.<String,Object>of("action",Map.of("type","SKIP","content","保留原文"),"evidenceEventIds",List.of("event-uuid"));
        assertEquals(full,WerewolfAiProtocol.decode(o,full,refs).output());
    }
    @Test void explicitFalseAndOtherGamesNeverAcquireHealingPermission() {
        var raw=Map.<String,Object>of("action",Map.of("type","NIGHT_ACTION","nightAction","WITCH_SAVE","useHeal",false));
        assertEquals(raw,WerewolfAiProtocol.decode(observation("werewolf"),raw, new WerewolfAiProtocol.References(Map.of(),Map.of())).output());
        var missing=Map.<String,Object>of("action",Map.of("type","NIGHT_ACTION","nightAction","WITCH_SAVE"));
        assertEquals(missing,WerewolfAiProtocol.decode(observation("undercover"),missing,new WerewolfAiProtocol.References(Map.of(),Map.of())).output());
    }
}
