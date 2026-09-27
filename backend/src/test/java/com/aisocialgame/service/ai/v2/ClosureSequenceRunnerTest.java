package com.aisocialgame.service.ai.v2;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.aisocialgame.engine.v2.RuleSupport.*;

class ClosureSequenceRunnerTest {
    @Test void allGamesCoverActualEvidenceActionAndNextRoundIncludingFallback() throws Exception {
        var fixtures=new MilestoneClosureScenarios();List<MilestoneClosureScenarios.Sample> samples=new ArrayList<>();
        List<Map<String,Object>> exported=new ArrayList<>();
        fixtures.sequences(s->{
            samples.add(s);var decision=AiTurnDecision.fallback(s.adapter().fallback(s.observation()));
            var row=new LinkedHashMap<String,Object>(s.metadata());row.put("coverage",s.coverage());row.put("observation",s.observation());row.put("decision",decision);row.put("status","LOCAL_FALLBACK");exported.add(row);return decision;
        });
        var document=MilestoneClosureScenarios.header("offline-rule-sequences","SYNTHETIC_TEST");document.put("samples",exported);
        MilestoneClosureScenarios.JSON.writerWithDefaultPrettyPrinter().writeValue(java.nio.file.Path.of("target/closure-sequence-offline-evidence.json").toFile(),document);
        assertEquals(48,samples.size());
        for(int i=0;i<samples.size();i+=4){
            var series=samples.subList(i,i+4);
            assertEquals(ClosureSequenceRunner.STEPS,series.stream().map(s->text(s.expected().get("stepId"))).toList());
            var first=series.get(0).observation();var second=series.get(1).observation();var fourth=series.get(3).observation();
            assertTrue(first.events().stream().anyMatch(e->"PUBLIC".equals(e.get("visibility"))));
            assertFalse(strings(series.get(1).coverage().get("requiredVisibleEventIds")).isEmpty());
            assertTrue(second.events().stream().anyMatch(e->"turtle_soup".equals(first.gameId())?"NO".equals(map(e.get("data")).get("verdict")):text(e.get("message")).contains("撤回")));
            for(var sample:series){assertEquals("APPLIED",sample.coverage().get("status"));assertEquals("LEGAL_FALLBACK",sample.coverage().get("origin"));assertEquals(sample.observation().phase(),sample.coverage().get("phase"));}
            if(!"turtle_soup".equals(first.gameId()))assertTrue(fourth.round()>first.round());
            else assertTrue(number(series.get(3).coverage().get("cycle"),0)>number(series.get(0).coverage().get("cycle"),0));
            assertTrue(maps(fourth.memory().get("recentDecisions")).contains(map(series.get(2).coverage().get("recordedDecision"))));
        }
    }
    @Test void changingAndKeepingJudgmentsAndBrokenPromiseAreRecordedWithoutForcingVotes()throws Exception{
        for(boolean change:List.of(false,true)){
            List<MilestoneClosureScenarios.Sample> rows=new ArrayList<>();
            new MilestoneClosureScenarios().sequences(s->{
                rows.add(s);var o=s.observation();String step=text(s.expected().get("stepId"));
                var selected=s.adapter().fallback(o);String speech=selected.getContent();Map<String,Object> updates=new LinkedHashMap<>();
                String target=o.players().stream().map(p->text(p.get("playerId"))).filter(id->!id.equals(o.actorId())).findFirst().orElseThrow();
                if("INITIAL".equals(step)&&!"turtle_soup".equals(o.gameId())){
                    speech="我本轮投给"+target;selected.setContent(speech);
                    updates.put("commitments",List.of(Map.of("text",speech,"sourceQuote",speech,"action",Map.of("kind","VOTE","targetPlayerId",target,"ballot","NORMAL"),"roundOffset",0)));
                }
                if("COUNTEREVIDENCE".equals(step)){
                    speech=change?"刚才的更正使我改变判断，我先保留其他解释。":"我听到了更正，但目前仍保留先前判断。";
                    selected=action("turtle_soup".equals(o.gameId())?"DISCUSS":"ANSWER_PLAYER",speech, "werewolf".equals(o.gameId())?o.legalActions().stream().filter(a->a.type().equals("ANSWER_PLAYER")).findFirst().orElseThrow().targets().getFirst():null);
                }
                if("ACTION".equals(step))selected=action("turtle_soup".equals(o.gameId())?"PASS":"SKIP",null,null);
                if("CONTINUITY".equals(step)&&!"turtle_soup".equals(o.gameId())){
                    assertTrue(maps(o.memory().get("commitments")).stream().anyMatch(c->"NOT_FULFILLED".equals(c.get("status"))),o.memory().toString());
                    assertTrue(maps(o.memory().get("recentDecisions")).stream().anyMatch(c->"SKIP".equals(c.get("action"))));
                }
                return new AiTurnDecision(selected,selected.getContent(),Map.of(),List.of(),updates,false,Map.of());
            });
            assertEquals(48,rows.size());for(var row:rows)assertEquals("MODEL",row.coverage().get("origin"));
        }
    }
}
