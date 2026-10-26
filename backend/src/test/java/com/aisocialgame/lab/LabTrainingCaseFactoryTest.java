package com.aisocialgame.lab;

import com.aisocialgame.engine.v2.werewolf.WerewolfRuleSet;
import com.aisocialgame.model.*;
import com.aisocialgame.repository.*;
import com.aisocialgame.service.ai.config.*;
import com.aisocialgame.service.ai.v2.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.aisocialgame.engine.v2.RuleSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LabTrainingCaseFactoryTest {
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    final PersonaRepository personas=new PersonaRepository();
    final AiTurnJobRepository jobs=mock(AiTurnJobRepository.class);
    final AiDecisionTraceRepository traces=mock(AiDecisionTraceRepository.class);
    final LocalAiDebugTurnRepository debug=mock(LocalAiDebugTurnRepository.class);
    final GameStateRepository states=mock(GameStateRepository.class);
    final GameArchiveRepository archives=mock(GameArchiveRepository.class);
    final AiPersonaMemoryRepository memories=mock(AiPersonaMemoryRepository.class);
    final WerewolfRuleSet rules=new WerewolfRuleSet();
    final LabTrainingCaseFactory factory=new LabTrainingCaseFactory(rules,new ObservationFactory(personas,new AiMemoryServiceV2(memories,personas)),jobs,traces,debug,states,archives,json);

    @Test void realTransitionsCreateEightDevelopmentAndEightIndependentHoldoutFamiliesAcrossFourPersonas() {
        var experiment=experiment();var cases=factory.build(experiment,"run-one");
        assertEquals(40,cases.size());assertEquals(40,cases.stream().map(c->c.get("id")).distinct().count());
        var development=cases.stream().filter(c->"DEVELOPMENT".equals(c.get("purpose"))).toList();
        var holdout=cases.stream().filter(c->"HOLDOUT".equals(c.get("purpose"))).toList();assertEquals(8,development.size());assertEquals(32,holdout.size());
        Set<Object> sources=new HashSet<>(development.stream().map(c->c.get("sourceGroup")).toList());
        assertTrue(holdout.stream().noneMatch(c->sources.contains(c.get("sourceGroup"))));assertEquals(8,holdout.stream().map(c->c.get("sourceGroup")).distinct().count());
        for(String persona:List.of("ai1","ai2","ai3","ai4")) {
            assertEquals(2,development.stream().filter(c->persona.equals(c.get("personaId"))).count());assertEquals(8,holdout.stream().filter(c->persona.equals(c.get("personaId"))).count());
        }
        for(var row:cases) {
            var data=map(row.get("data"));var observation=map(data.get("observation"));String actor=text(observation.get("actorId")),role=text(map(observation.get("self")).get("role"));
            assertEquals(row.get("contentHash"),LabTrainingInput.fingerprint(data));assertEquals(row.get("personaId"),map(observation.get("persona")).get("id"));
            assertEquals("RULE_CONSTRUCTED",row.get("sourceType"));assertEquals("NOT_SENT_RULE_CONSTRUCTED",data.get("originalPromptStatus"));
            assertTrue(maps(data.get("actualPrompt")).isEmpty());assertTrue(map(data.get("actualAction")).isEmpty());
            assertTrue(text(map(data.get("source")).get("archiveId")).isEmpty(),"constructed position must not masquerade as a persisted match");
            assertFalse(maps(observation.get("legalActions")).isEmpty());assertFalse(observation.containsKey("outcomeTruth"));assertFalse(observation.containsKey("expectedRubric"));
            assertTrue(strings(data.get("validEvidenceIds")).contains("RULE:roleCounts"));assertTrue(strings(data.get("validEvidenceIds")).stream().anyMatch(id->id.startsWith("KNOWLEDGE:")));
            var privateFacts=map(observation.get("privateFacts"));
            if(!"WEREWOLF".equals(role)){assertFalse(privateFacts.containsKey("wolfTeam"));assertFalse(privateFacts.containsKey("wolfCouncil"));}
            if(!"SEER".equals(role))assertFalse(privateFacts.containsKey("seerChecks"));
            if(!"WITCH".equals(role))assertFalse(privateFacts.containsKey("wolfTarget"));
            for(var player:maps(observation.get("players"))) if(!actor.equals(player.get("playerId")) && !("WEREWOLF".equals(role) && strings(privateFacts.get("wolfTeam")).contains(text(player.get("playerId")))))
                assertFalse(player.containsKey("role"),"other identity leaked into player observation");
            assertTrue(new LabTrainingInput().prepare(" ".repeat(4096),Map.of("case",data)).ready(),"complete constructed case must fit with reserved instruction space: "+data.get("title"));
        }
        for(var group:holdout.stream().map(c->c.get("sourceGroup")).distinct().toList()) {
            var variants=holdout.stream().filter(c->group.equals(c.get("sourceGroup"))).toList();
            assertEquals(4,variants.size());assertEquals(1,variants.stream().map(c->LabTrainingInput.write(map(map(c.get("data")).get("observation")).get("events"))).distinct().count(),"persona variants share one exact frozen source position");
        }
        assertTrue(holdout.stream().anyMatch(c->"HUNTER".equals(map(map(map(c.get("data")).get("observation")).get("self")).get("role"))));
    }

    @Test void realSourcesKeepExactPersonalityPromptAndActionWhileOversizedSourceIsExcludedAndMissingPromptIsNotInvented() {
        var experiment=experiment();var fixture=factory.build(experiment,"setup").stream().filter(c->"ai1".equals(c.get("personaId"))).findFirst().orElseThrow();
        var observation=map(map(fixture.get("data")).get("observation"));String archive="archive-2314bc39-debc-4ac1-aefb-761ed2c9df1b";
        observation.put("instanceId",archive);
        var huge=copy(observation);huge.put("knowledge",List.of("独".repeat(40_000)));
        var rejected=job("too-large",archive,huge);var missing=job("missing-prompt",archive,observation);var exact=job("exact-prompt",archive,observation);
        when(jobs.findByInstanceIdOrderByCreatedAtAsc(archive)).thenReturn(List.of(rejected,missing,exact));
        var rawAction=Map.<String,Object>of("type","VOTE","targetPlayerId","abstain");
        when(traces.findByRoomIdAndInstanceIdAndActorPlayerId("source-room",archive,text(observation.get("actorId"))))
                .thenReturn(List.of(trace(rejected,1L,rawAction),trace(missing,2L,rawAction),trace(exact,3L,rawAction)));
        var stored=new LocalAiDebugTurn();stored.id=exact.getId();stored.actorId=exact.getActorId();stored.instanceId=archive;
        var prompt=List.of(Map.of("role","system","content","原始玩家指令，不是教练指令。"),Map.of("role","user","content",LabTrainingInput.write(observation)));
        stored.payload=Map.of("attempts",Map.of("0",Map.of("messages",prompt,"requestId","original-request")));
        when(debug.findById(exact.getId())).thenReturn(Optional.of(stored));
        var cases=factory.build(experiment,"real-run");
        assertEquals(8,cases.stream().filter(c->"DEVELOPMENT".equals(c.get("purpose"))).count());assertEquals(32,cases.stream().filter(c->"HOLDOUT".equals(c.get("purpose"))).count());
        var excluded=cases.stream().filter(c->"EXCLUDED".equals(c.get("purpose"))).findFirst().orElseThrow();assertEquals("TOO_LARGE",map(excluded.get("data")).get("exclusionReason"));assertEquals(huge,map(excluded.get("data")).get("observation"));
        var actual=cases.stream().filter(c->"REAL_GAME".equals(c.get("sourceType")) && "DEVELOPMENT".equals(c.get("purpose"))).toList();assertEquals(2,actual.size());
        var a=map(actual.get(0).get("data"));assertEquals("MISSING",a.get("originalPromptStatus"));assertEquals(List.of(),a.get("actualPrompt"));
        var b=map(actual.get(1).get("data"));assertEquals("EXACT",b.get("originalPromptStatus"));assertEquals(prompt,b.get("actualPrompt"));assertEquals(rawAction,b.get("actualAction"));assertEquals(observation,b.get("observation"));
        assertEquals("ai1",actual.get(1).get("personaId"));assertEquals("game:"+archive,actual.get(1).get("sourceGroup"));
        stored.payload=Map.of();assertEquals(prompt,b.get("actualPrompt"));
    }

    @Test void developmentSelectionIncludesConversationAndAnotherDecisionInsteadOfOnlyEarliestNightActions() {
        var experiment=experiment();var fixtures=factory.build(experiment,"selection-fixture");
        var reply=fixtures.stream().filter(c->"ai1".equals(c.get("personaId")) && "DEVELOPMENT".equals(c.get("purpose")) && "LEGAL_DECEPTION".equals(map(c.get("data")).get("focus"))).findFirst().orElseThrow();
        var night=fixtures.stream().filter(c->"ai1".equals(c.get("personaId")) && "TEAM_COORDINATION".equals(map(c.get("data")).get("focus"))).findFirst().orElseThrow();
        String archive="archive-2314bc39-debc-4ac1-aefb-761ed2c9df1b";
        var nightView=map(map(night.get("data")).get("observation"));nightView.put("instanceId",archive);
        var replyView=map(map(reply.get("data")).get("observation"));replyView.put("instanceId",archive);
        var firstNight=job("early-night",archive,nightView);var secondNight=job("another-night",archive,nightView);var conversation=job("public-answer",archive,replyView);
        when(jobs.findByInstanceIdOrderByCreatedAtAsc(archive)).thenReturn(List.of(firstNight,secondNight,conversation));
        when(traces.findByRoomIdAndInstanceIdAndActorPlayerId("source-room",archive,text(nightView.get("actorId"))))
                .thenReturn(List.of(trace(firstNight,1L,Map.of("type","SKIP")),trace(secondNight,2L,Map.of("type","SKIP")),trace(conversation,3L,Map.of("type","ANSWER_PLAYER","content","我仍然保留自己的公开立场。"))));
        var selected=factory.build(experiment,"selection-run").stream().filter(c->"DEVELOPMENT".equals(c.get("purpose")) && "REAL_GAME".equals(c.get("sourceType"))).toList();
        assertEquals(List.of("public-answer","early-night"),selected.stream().map(c->map(map(c.get("data")).get("source")).get("jobId")).toList());
    }

    @Test void incompatibleHistoricalBoardIsRecordedButNeverBecomesNineSeatTeachingMaterial() {
        var experiment=experiment();var fixture=factory.build(experiment,"rules-fixture").getFirst();
        var observation=map(map(fixture.get("data")).get("observation"));String archive="archive-98d8320c-26f0-4988-8fbe-72264e7f07a9";
        observation.put("instanceId",archive);var sourceRules=map(observation.get("rules"));sourceRules.put("playerCount",6);observation.put("rules",sourceRules);
        var source=job("six-seat-source",archive,observation);when(jobs.findByInstanceIdOrderByCreatedAtAsc(archive)).thenReturn(List.of(source));
        when(traces.findByRoomIdAndInstanceIdAndActorPlayerId("source-room",archive,source.getActorId())).thenReturn(List.of(trace(source,1L,Map.of("type","SKIP"))));
        var material=factory.build(experiment,"rules-run");
        assertEquals(8,material.stream().filter(c->"DEVELOPMENT".equals(c.get("purpose"))).count());
        var excluded=material.stream().filter(c->"EXCLUDED".equals(c.get("purpose"))).toList();assertEquals(1,excluded.size());
        assertEquals("INCOMPATIBLE_RULES",map(excluded.getFirst().get("data")).get("exclusionReason"));
        assertEquals(observation,map(excluded.getFirst().get("data")).get("observation"));
        assertTrue(material.stream().filter(c->"DEVELOPMENT".equals(c.get("purpose"))).noneMatch(c->"REAL_GAME".equals(c.get("sourceType"))));
    }

    @Test void replacementChoosesSmallestCompleteSamePersonaSourceAndNeverReusesAnyRecordedJob() {
        var experiment=experiment();var run=run(experiment);String archive="replacement-source";
        var original=original(experiment,run,archive);String originalJson=LabTrainingInput.write(original.data),originalHash=original.contentHash;
        completed(archive,"WEREWOLF");var view=map(original.data.get("observation"));
        var larger=copy(view);var knowledge=new ArrayList<>(strings(larger.get("knowledge")));knowledge.add("额外的完整历史材料".repeat(100));larger.put("knowledge",knowledge);
        var huge=copy(view);huge.put("knowledge",List.of("不可截断的过大证据".repeat(6000)));
        var wrongPersona=copy(view);wrongPersona.put("persona",Map.of("id","ai2"));
        var used=job("already-used",archive,view);var large=job("large-complete",archive,larger);var small=job("small-complete",archive,view);
        var missing=job("missing-prompt",archive,view);var different=job("other-persona",archive,wrongPersona);var oversized=job("oversized",archive,huge);
        var sources=List.of(used,large,small,missing,different,oversized);when(jobs.findByInstanceIdOrderByCreatedAtAsc(archive)).thenReturn(sources);
        when(traces.findByRoomIdAndInstanceIdAndActorPlayerId("source-room",archive,text(view.get("actorId"))))
                .thenReturn(sources.stream().map(j->trace(j,1L,Map.of("type","VOTE","targetPlayerId","abstain"))).toList());
        for(var source:List.of(used,large,small,different,oversized))exactPrompt(source);
        var chosen=factory.replacement(experiment,run,original,List.of(original)).orElseThrow();var data=map(chosen.get("data"));
        assertEquals("small-complete",map(data.get("source")).get("jobId"));assertEquals(original.personaId,chosen.get("personaId"));
        assertEquals(original.participantId,chosen.get("participantId"));assertEquals("game:"+archive,chosen.get("sourceGroup"));assertEquals("DEVELOPMENT",chosen.get("purpose"));
        assertEquals(original.id,data.get("replacementForCaseId"));assertEquals("EXACT",data.get("originalPromptStatus"));
        assertEquals(chosen.get("contentHash"),LabTrainingInput.fingerprint(data));assertTrue(number(map(data.get("inputPreflight")).get("minimumCodePoints"),0)<32000);
        assertEquals(chosen,factory.replacement(experiment,run,original,List.of(original)).orElseThrow(),"same source and reserved request identity reproduce the immutable replacement");
        var recorded=caseEntity(chosen);var next=factory.replacement(experiment,run,original,List.of(original,recorded)).orElseThrow();
        assertEquals("large-complete",map(map(next.get("data")).get("source")).get("jobId"));
        assertTrue(factory.replacement(experiment,run,original,List.of(original,recorded,caseEntity(next))).isEmpty());
        assertEquals(originalJson,LabTrainingInput.write(original.data));assertEquals(originalHash,original.contentHash);assertEquals("DEVELOPMENT",original.purpose);
        var frozenObservation=(Map<String,Object>)map(chosen.get("data")).get("observation");
        assertThrows(UnsupportedOperationException.class,()->frozenObservation.put("changed",true));
    }

    @Test void replacementNeverSearchesHeldOutUnfinishedOrAbortedArchives() {
        for(String exclusion:List.of("HOLDOUT","UNFINISHED","ABORTED")) {
            var experiment=experiment();var run=run(experiment);String archive="excluded-"+exclusion;var original=original(experiment,run,archive);
            var all=new ArrayList<LabTrainingCase>();all.add(original);
            if("HOLDOUT".equals(exclusion)) {
                completed(archive,"GOOD");var holdout=new LabTrainingCase();holdout.runId=run.id;holdout.purpose="HOLDOUT";holdout.sourceGroup=original.sourceGroup;all.add(holdout);
            } else if("ABORTED".equals(exclusion))completed(archive,"ABORTED");
            else {var incomplete=new GameArchive();incomplete.setId(archive);incomplete.setGameId("werewolf");incomplete.setPlayerCount(9);incomplete.setWinner("GOOD");when(archives.findById(archive)).thenReturn(Optional.of(incomplete));}
            assertTrue(factory.replacement(experiment,run,original,all).isEmpty());verify(jobs,never()).findByInstanceIdOrderByCreatedAtAsc(archive);
        }
    }

    @Test void minimumLessonRetainsEveryKnownSourceAndBuildsTheExactReservedPracticePromptWithoutFutureTruth() {
        var experiment=experiment();var run=run(experiment);var original=original(experiment,run,"minimum-source");
        var data=copy(original.data);data.put("actualPrompt",List.of(Map.of("role","system","content","完整历史规则"),Map.of("role","user","content",LabTrainingInput.write(data.get("observation")))));
        data.put("originalPromptStatus","EXACT");data.put("outcomeTruth",Map.of("winner","WEREWOLF","hiddenFuture","只用于教练事后分析"));
        String before=LabTrainingInput.write(data),taskId=UUID.randomUUID().toString();
        var prepared=factory.minimumTeachingInput(run,data,original.participantId,taskId);assertTrue(prepared.ready());
        var lesson=LabTrainingInput.decode(prepared.encoded());assertEquals(data.get("observation"),lesson.get("observation"));assertEquals(data.get("actualPrompt"),lesson.get("actualPrompt"));
        assertEquals(data.get("actualAction"),lesson.get("sourceAction"));assertEquals(data.get("outcomeTruth"),lesson.get("outcomeTruth"));assertEquals(data.get("validEvidenceIds"),lesson.get("validEvidenceIds"));
        var prompt=maps(lesson.get("practicePrompt"));var playerEnvelope=jsonRead(text(prompt.getLast().get("content")));var playerPayload=LabTrainingInput.decode(playerEnvelope);
        var playerView=map(playerPayload.get("observation"));assertEquals(taskId,map(map(playerView.get("memory")).get("lab")).get("matchId"));
        assertEquals(map(data.get("observation")).get("rules"),playerView.get("rules"));assertFalse(playerPayload.containsKey("outcomeTruth"));assertFalse(playerPayload.containsKey("caseRubric"));
        assertTrue(map(map(lesson.get("practice")).get("action")).isEmpty(),"a future model action is never invented by preflight");assertEquals(before,LabTrainingInput.write(data));
    }

    private LabTrainingRun run(LabExperiment experiment) {
        var run=new LabTrainingRun();run.experimentId=experiment.id;run.configuration.put("roster",experiment.configuration.get("roster"));run.configuration.put("rules",experiment.configuration.get("rules"));run.configuration.put("baseStrategy",Map.of());return run;
    }
    private LabTrainingCase original(LabExperiment experiment,LabTrainingRun run,String archive) {
        var row=factory.build(experiment,"replacement-fixture").stream().filter(c->"DEVELOPMENT".equals(c.get("purpose"))&&"ai1".equals(c.get("personaId"))).findFirst().orElseThrow();
        var original=caseEntity(row);original.runId=run.id;original.sourceType="REAL_GAME";original.sourceGroup="game:"+archive;original.data=copy(original.data);
        var view=map(original.data.get("observation"));view.put("instanceId",archive);original.data.put("observation",view);
        original.data.put("source",Map.of("jobId","already-used","archiveId",archive,"roomId","source-room"));original.contentHash=LabTrainingInput.fingerprint(original.data);return original;
    }
    private LabTrainingCase caseEntity(Map<String,Object> row) {
        var value=new LabTrainingCase();value.id=text(row.get("id"));value.runId=text(row.get("runId"));value.purpose=text(row.get("purpose"));value.sourceType=text(row.get("sourceType"));value.sourceGroup=text(row.get("sourceGroup"));
        value.personaId=text(row.get("personaId"));value.participantId=text(row.get("participantId"));value.data=map(row.get("data"));value.contentHash=text(row.get("contentHash"));return value;
    }
    private void completed(String id,String winner) {
        var archive=new GameArchive();archive.setId(id);archive.setGameId("werewolf");archive.setPlayerCount(9);archive.setWinner(winner);archive.setFinishedAt(java.time.LocalDateTime.of(2026,10,26,12,0));when(archives.findById(id)).thenReturn(Optional.of(archive));
    }
    private void exactPrompt(AiTurnJob job) {
        var value=new LocalAiDebugTurn();value.id=job.getId();value.actorId=job.getActorId();value.instanceId=job.getInstanceId();
        value.payload=Map.of("attempts",Map.of("0",Map.of("messages",List.of(Map.of("role","system","content","原始完整玩家系统提示"),Map.of("role","user","content",LabTrainingInput.write(job.getObservation()))),"requestId","request-"+job.getId())));
        when(debug.findById(job.getId())).thenReturn(Optional.of(value));
    }
    private Map<String,Object> jsonRead(String value) {try{return json.readValue(value,Map.class);}catch(Exception e){throw new IllegalStateException(e);}}
    private AiTurnJob job(String id,String archive,Map<String,Object> observation) {
        var job=new AiTurnJob();job.setId(id);job.setInstanceId(archive);job.setRoomId("source-room");job.setActorId(text(observation.get("actorId")));job.setKind(text(observation.get("turnKind")));job.setStatus("SUCCEEDED");job.setObservation(copy(observation));return job;
    }
    private AiDecisionTrace trace(AiTurnJob job,Long id,Map<String,Object> action) {
        var trace=new AiDecisionTrace();trace.setId(id);trace.setQuality(Map.of("jobId",job.getId()));trace.setValidDecision(true);trace.setFallback(false);trace.setRawOutput(Map.of("action",action));return trace;
    }
    private Map<String,Object> copy(Map<String,Object> value) {return json.convertValue(value,Map.class);}
    private LabExperiment experiment() {
        var experiment=new LabExperiment();experiment.configuration.put("gameId","werewolf");experiment.configuration.put("playerCount",9);experiment.configuration.put("randomSeed",5678123L);experiment.configuration.put("rules",LabGameConfig.werewolfRules(9,Map.of()));
        var roster=new ArrayList<Map<String,Object>>();
        for(int seat=0;seat<9;seat++) {
            var persona=personas.findAll().get(seat%4);var profile=new LinkedHashMap<String,Object>();
            profile.put("participantId","training-participant-"+seat);profile.put("agentId","source-"+persona.getId());profile.put("personaId",persona.getId());profile.put("name",persona.getName()+seat);profile.put("persona",json.convertValue(persona,Map.class));
            profile.put("modelSelection",new AiModelSelection(AiFunctionCode.WEREWOLF,"fixture",AiModelSelection.ThinkingMode.ENABLED,"LOW",1).toMap());roster.add(profile);
        }
        experiment.configuration.put("roster",roster);return experiment;
    }
}
