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
        assertEquals("training-dictionary-v2",encoded.get("format"));
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

    @Test void nestedHistoricalPromptsReuseWholeSystemTextAndOrderedObjectFieldsWithoutChangingAnySourceByte() {
        String system="完整历史系统提示，不得删除或改写原始规则与限制。🙂\n".repeat(180);
        var events=new ArrayList<Map<String,Object>>();
        for(int i=0;i<20;i++) {
            var event=new LinkedHashMap<String,Object>();
            event.put("eventId","original-event-"+i);event.put("eventType","SPEECH");event.put("visibleToActorId","actor");
            event.put("sourcePlayerId","player-"+i);event.put("targetPlayerId",null);
            event.put("originalStatement","不确定其他人的身份；这是第 "+i+" 条完整可见证据。");event.put("roundNumber",i/4+1);
            events.add(event);
        }
        var observation=Map.<String,Object>of("actorId","actor","rules",Map.of("playerCount",9,"hasGuard",false),"events",events);
        var originalPrompt=List.of(Map.of("role","system","content",system),Map.of("role","user","content",LabTrainingInput.write(Map.of("observation",observation))));
        var oldEncoding=Map.<String,Object>of("format","training-dictionary-v1","dictionary",List.of(observation),"value",Map.of("observation",Map.of("$ref",0)));
        var practicePrompt=List.of(Map.of("role","system","content",system+"新增说明只解释字典，不替换历史系统提示。"),
                Map.of("role","user","content",LabTrainingInput.write(oldEncoding)));
        var source=new LinkedHashMap<String,Object>();source.put("observation",observation);source.put("actualPrompt",originalPrompt);
        source.put("practicePrompt",practicePrompt);source.put("literal",Map.of("$concat",List.of("原始","字段"),"$object",List.of(99,"不解释为协议")));
        var encoded=LabTrainingInput.encode(source);var restored=LabTrainingInput.decode(encoded);
        assertEquals(LabTrainingInput.write(source),LabTrainingInput.write(restored));
        assertArrayEquals(LabTrainingInput.write(source).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                LabTrainingInput.write(restored).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String wire=LabTrainingInput.write(encoded);assertTrue(wire.contains("\"$concat\""));assertTrue(wire.contains("\"$object\""));
        assertTrue(wire.length()<LabTrainingInput.write(source).length()*0.65,"both nested prompts and the complete observation should share material");
        assertEquals(practicePrompt,restored.get("practicePrompt"));
    }

    @Test void oldDictionaryKeepsNewReservedKeysLiteralAndNewMalformedFieldRowsFailClosed() {
        var literal=Map.<String,Object>of("a",Map.of("$concat",List.of("one","two")),"b",Map.of("$object",List.of(3,"original")));
        assertEquals(literal,LabTrainingInput.decode(Map.of("format","training-dictionary-v1","dictionary",List.of(),"value",literal)));
        for(Object row:List.of(Map.of("$object",List.of(0,"missing second value")),Map.of("$object",List.of(1,"outside schema")),Map.of("$concat",List.of("text",123))))
            assertThrows(IllegalArgumentException.class,()->LabTrainingInput.decode(Map.of("format","training-dictionary-v2","schemas",List.of(List.of("a","b")),"dictionary",List.of(),"value",Map.of("field",row))));
    }
}
