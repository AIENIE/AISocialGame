package com.aisocialgame.lab;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LabTrainingInputTest {
    @Test void exactRoundTripPreservesPromptTextNullsOrderReservedKeysAndDifferentPrivateViews() {
        var visible=new LinkedHashMap<String,Object>();visible.put("eventId","event-"+"e".repeat(36));visible.put("message","这是一条完整的可见原始证据🙂");visible.put("targetId",null);
        var wolf=Map.<String,Object>of("role","WEREWOLF","wolfTeam",List.of("wolf-one","wolf-two"),"events",List.of(visible));
        var villager=Map.<String,Object>of("role","VILLAGER","events",List.of(visible));
        var literal=Map.of("$ref",123,"$json","not an encoded instruction","$literal",Map.of("$ref",0));
        var caseA=Map.<String,Object>of("observation",wolf,"actualPrompt",List.of(Map.of("role","user","content",LabTrainingInput.write(wolf))),"marker",literal);
        var caseB=Map.<String,Object>of("observation",villager,"actualPrompt",List.of(Map.of("role","user","content",LabTrainingInput.write(villager))));
        var input=new LinkedHashMap<String,Object>();input.put("cases",List.of(caseA,caseB,caseA,caseB));input.put("truth",null);
        var encoded=LabTrainingInput.encode(input);var restored=LabTrainingInput.decode(encoded);
        assertEquals(input,restored);assertEquals(LabTrainingInput.write(input),LabTrainingInput.write(restored));
        assertTrue(LabTrainingInput.write(encoded).length()<LabTrainingInput.write(input).length());
        assertEquals("training-dictionary-v1",encoded.get("format"));
        assertThrows(UnsupportedOperationException.class,()->encoded.put("value",Map.of()));
    }

    @Test void admissionCountsEverySerializedMessageAsUnicodeCodePointsAndDoesNotTruncate() {
        var codec=new LabTrainingInput();var payload=Map.<String,Object>of("case",Map.of("complete","原始信息"));
        int overhead=codec.prepare("",payload).codePoints();
        var boundary=codec.prepare("🙂".repeat(LabTrainingInput.MAX_CODE_POINTS-overhead),payload);
        assertTrue(boundary.ready());assertEquals(32_000,boundary.codePoints());
        String serialized=LabTrainingInput.write(boundary.messages());
        assertEquals(boundary.codePoints(),serialized.codePointCount(0,serialized.length()));assertTrue(serialized.length()>32_000);
        var tooLarge=codec.prepare("🙂".repeat(LabTrainingInput.MAX_CODE_POINTS-overhead+1),payload);
        assertEquals("TOO_LARGE",tooLarge.status());assertEquals(32_001,tooLarge.codePoints());assertTrue(tooLarge.messages().isEmpty());
        assertEquals(payload,LabTrainingInput.decode(tooLarge.encoded()),"overflow retains its complete audit source, never a shortened sendable request");
    }

    @Test void hashIsStableAcrossMapOrderButSeparatesChangedEvidenceAndInvalidReferencesFailClosed() {
        assertEquals(LabTrainingInput.fingerprint(Map.of("a",1,"b",List.of("x","y"))),LabTrainingInput.fingerprint(new LinkedHashMap<>(Map.of("b",List.of("x","y"),"a",1))));
        assertNotEquals(LabTrainingInput.fingerprint(Map.of("evidence",List.of("one","two"))),LabTrainingInput.fingerprint(Map.of("evidence",List.of("two","one"))));
        assertThrows(IllegalArgumentException.class,()->LabTrainingInput.decode(Map.of("format","training-dictionary-v1","dictionary",List.of(Map.of("$ref",0)),"value",Map.of("$ref",0))));
    }
}
