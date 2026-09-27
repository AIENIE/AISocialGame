package com.aisocialgame.service.ai.v2;

import com.aisocialgame.engine.v2.*;
import com.aisocialgame.engine.v2.undercover.*;
import com.aisocialgame.engine.v2.werewolf.*;
import com.aisocialgame.engine.v2.turtlesoup.*;
import com.aisocialgame.model.*;
import com.aisocialgame.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import static com.aisocialgame.engine.v2.RuleSupport.*;
import static org.mockito.Mockito.mock;

/** Frozen evaluation inventory. Scripted peers use the real rules; only evaluated decisions call the supplied generator. */
final class MilestoneClosureScenarios {
    static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();
    final UndercoverWordCatalog words=new UndercoverWordCatalog();
    final TurtleSoupCaseCatalog soups=new TurtleSoupCaseCatalog(JSON);
    final List<GameRuleSet> rules=List.of(new UndercoverRuleSet(words),new WerewolfRuleSet(),new TurtleSoupRuleSet(soups));
    final AiRealismScenarioFixtures fixtures=new AiRealismScenarioFixtures(rules,words,soups);
    record Sample(String id,String group,String personaId,GameAiAdapter adapter,VisibleObservation observation,Map<String,Object> expected) {
        Map<String,Object> metadata() {
            String sequence="SEQUENCE".equals(group)?observation.gameId()+":continuity-v2":"";
            String scenario="SEQUENCE".equals(group)?"continuity-v2":"HOST".equals(group)?id:id.substring(0,id.lastIndexOf(':'));
            return Map.of("id",id,"group",group,"personaId",personaId,"gameId",observation.gameId(),"scenarioId",scenario,"sequenceId",sequence,"stepId",expected.getOrDefault("stepId",""));
        }
        Map<String,Object> coverage() { return (Map<String,Object>) expected.getOrDefault("coverage",new LinkedHashMap<>()); }
    }
    interface Evaluate { AiTurnDecision run(Sample sample) throws Exception; }
    static String sourceFingerprint() throws Exception {
        var process=new ProcessBuilder("python", "../scripts/windows/support/closure_identity.py", "fingerprint", "..").redirectError(ProcessBuilder.Redirect.INHERIT).start();
        var value=JSON.readTree(process.getInputStream());
        if(process.waitFor()!=0)throw new IllegalStateException("Fingerprint failed");
        return value.path("sourceFingerprint").asText();
    }
    static Map<String,Object> header(String batch,String kind) throws Exception {
        String source=sourceFingerprint();
        String build=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(("closure-build-v1:"+source).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var value=new LinkedHashMap<String,Object>();value.put("evaluationSchemaVersion",2);value.put("evaluationSetVersion","closure-v2");value.put("batchId",batch);value.put("evidenceKind",kind);
        value.put("sourceFingerprint",source);value.put("buildId",build);value.put("promptVersion",AiTurnGenerator.PROMPT_VERSION);value.put("inputFormatVersion",AiPromptProjection.VERSION);value.put("memoryFormatVersion",AiMemoryEntries.FORMAT_VERSION);return value;
    }
    List<Sample> singles() throws Exception {
        List<Sample> result=new ArrayList<>();
        for(var scenario:fixtures.load()) for(var persona:PersonaPresets.all()) {
            var o=scenario.observation();Map<String,Object> profile=JSON.convertValue(persona,Map.class);
            profile.put("difficulty",AiRoundReflection.difficulty(2));
            var observation=new VisibleObservation(o.gameId(),o.instanceId(),o.phase(),o.round(),o.actorId(),o.turnKind(),o.self(),o.players(),o.rules(),o.events(),o.privateFacts(),o.legalActions(),profile,o.memory(),o.knowledge());
            result.add(new Sample(scenario.id()+":"+persona.getId(),"SINGLE",persona.getId(),scenario.adapter(),observation,scenario.expectations()));
        }
        return result;
    }
    List<Sample> hosts() {
        List<Sample> result=new ArrayList<>();var all=fixtures.turtleHostFixtures();
        var verdicts=List.of("YES","NO","UNKNOWN","NEEDS_CLARIFICATION","UNKNOWN","INVALID_PREMISE");
        for(int i=0;i<6;i++) {
            String caseId=soups.cases().get(i).id(),verdict=verdicts.get(i);
            var fixture=all.stream().filter(f -> caseId.equals(f.get("caseId")) && verdict.equals(f.get("expectedVerdict"))).findFirst().orElseThrow();
            result.add(new Sample("host:"+caseId+":"+verdict,"HOST","HOST",rules.get(2),fixtures.turtleHostObservation(fixture),fixture));
        }
        for(int i=0;i<4;i++) {
            String caseId=soups.cases().get(i).id();boolean correct=i%2==0;
            var fixture=all.stream().filter(f -> caseId.equals(f.get("caseId")) && "SUBMIT_SOLUTION".equals(f.get("type")) && Boolean.valueOf(correct).equals(f.get("expectedSolved"))).findFirst().orElseThrow();
            result.add(new Sample("host:"+caseId+":solution:"+correct,"HOST","HOST",rules.get(2),fixtures.turtleHostObservation(fixture),fixture));
        }
        for(int i=0;i<2;i++) {
            String caseId=soups.cases().get(i).id();
            var fixture=new LinkedHashMap<>(all.stream().filter(f -> caseId.equals(f.get("caseId")) && "YES".equals(f.get("expectedVerdict"))).findFirst().orElseThrow());
            fixture.put("expectedDuplicate",true);var o=fixtures.turtleHostObservation(fixture);var facts=new LinkedHashMap<>(o.privateFacts());
            facts.put("previousPropositions",List.of(Map.of("proposition",fixture.get("content"),"verdict","YES","requestId","prior-confirmed")));
            var input=new VisibleObservation(o.gameId(),o.instanceId(),o.phase(),o.round(),o.actorId(),o.turnKind(),o.self(),o.players(),o.rules(),o.events(),facts,o.legalActions(),o.persona(),o.memory(),o.knowledge());
            result.add(new Sample("host:"+caseId+":duplicate","HOST","HOST",rules.get(2),input,fixture));
        }
        return result;
    }
    List<String> sequenceIds() {
        List<String> ids=new ArrayList<>();for(var rule:rules)for(var persona:PersonaPresets.all())for(int step=1;step<=4;step++)ids.add("sequence:"+rule.gameId()+":"+persona.getId()+":"+step);return ids;
    }
    void sequences(Evaluate evaluator) throws Exception { new ClosureSequenceRunner(this).run(evaluator); }
}
