package com.aisocialgame.lab;

import com.aisocialgame.AiSocialGameApplication;
import com.aisocialgame.exception.ApiException;
import com.aisocialgame.model.*;
import com.aisocialgame.repository.*;
import com.aisocialgame.service.ai.config.*;
import com.aisocialgame.service.ai.v2.AiMemoryServiceV2;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static com.aisocialgame.engine.v2.RuleSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(classes=AiSocialGameApplication.class)
@ActiveProfiles("test")
@Transactional
class LabStrategyPublicationTest {
    @Autowired LabStrategyService strategies;
    @Autowired StrategyPublicationService publications;
    @Autowired LabStrategyVersionRepository versions;
    @Autowired StrategyPublicationRepository receipts;
    @Autowired LabExperimentRepository experiments;
    @Autowired LabMatchRepository matches;
    @Autowired AiTurnJobRepository jobs;
    @Autowired AiPersonaMemoryRepository memories;
    @Autowired AiMemoryServiceV2 memoryService;
    @Autowired LabStrategyAssessmentService assessments;
    @MockitoBean AiModelConfigService models;
    final AiModelSelection selection=new AiModelSelection(AiFunctionCode.WEREWOLF,"fixture",AiModelSelection.ThinkingMode.ENABLED,"LOW",1);
    final Map<String,Object> rules=Map.of("template","standard","playerCount",9,"witchRule","first_night","winCondition","side","hasLastWords","first_night");

    @BeforeEach void setup() {
        when(models.resolve(eq("werewolf"),any())).thenReturn(selection);
        for(String persona:List.of("ai1","ai2","ai3","ai4")) memories.findByPersonaIdAndGameIdAndRoleKey(persona,"werewolf","GENERAL_V2").ifPresent(memory->{
            memory.setStrategyPublicationId(null);memory.setApprovedSummary("");memory.setReviewStatus("PENDING");memories.save(memory);
        });
    }

    @Test void oldPublishAndWithdrawalRetriesReturnOriginalReceiptsWithoutChangingCurrentBinding() {
        var version=version("GENERAL");var publish=request("PUBLISH",version.id,List.of("ai1","ai2","ai3","ai4"));
        var first=publications.apply(publish,"fixture-admin");
        assertEquals(4,maps(first.get("bindings")).size());
        var withdraw=request("WITHDRAW",null,List.of("ai1","ai2","ai3","ai4"));
        var removed=publications.apply(withdraw,"fixture-admin");
        assertEquals(first,publications.apply(publish,"fixture-admin"));assertEquals(removed,publications.apply(withdraw,"fixture-admin"));
        for(var current:publications.bindings()) assertEquals(removed.get("id"),current.get("publicationId"));
        assertEquals(first,publications.inspect(publish.operationId()));
        assertEquals("STRATEGY_OPERATION_CONFLICT",assertThrows(ApiException.class,()->publications.apply(
                new StrategyPublicationService.Request(publish.operationId(),"WITHDRAW",null,publish.personaIds(),"TRIAL"),"fixture-admin")).getCode());
        assertEquals(2,receipts.count());
    }

    @Test void publicationOnlyBindsExplicitPersonasAndRejectsInvalidBatchBeforeChangingAnything() {
        var version=version("GENERAL");var first=publications.apply(request("PUBLISH",version.id,List.of("ai1")),"fixture-admin");
        assertEquals(1,publications.bindings().stream().filter(binding->binding.containsKey("strategyVersionId")).count());
        assertThrows(ApiException.class,()->publications.apply(request("PUBLISH",version.id,List.of("ai2","unknown-persona")),"fixture-admin"));
        assertEquals(first.get("id"),publications.bindings().stream().filter(binding->"ai1".equals(binding.get("personaId"))).findFirst().orElseThrow().get("publicationId"));
        assertEquals(1,receipts.count());
    }

    @Test void ordinaryGameKeepsFrozenStrategyAfterWithdrawalAndNextGameGetsLegacySnapshot() {
        var legacy=memories.findByPersonaIdAndGameIdAndRoleKey("ai1","werewolf","GENERAL_V2").orElseGet(()->new AiPersonaMemory("ai1","werewolf","GENERAL_V2"));
        legacy.setApprovedSummary("旧通用经验");legacy.setReviewStatus("APPROVED");memories.saveAndFlush(legacy);
        var version=version("GENERAL");publications.apply(request("PUBLISH",version.id,List.of("ai1")),"fixture-admin");
        var first=state();publications.freezeState(first);
        assertEquals(version.id,map(memoryService.snapshot(first,"actor").get("strategy")).get("versionId"));
        publications.apply(request("WITHDRAW",null,List.of("ai1")),"fixture-admin");
        publications.freezeState(first);
        assertEquals(version.id,map(memoryService.snapshot(first,"actor").get("strategy")).get("versionId"));
        var next=state();publications.freezeState(next);assertEquals("旧通用经验",memoryService.snapshot(next,"actor").get("approvedExperience"));
        legacy.setApprovedSummary("局中修改后的内容");memories.saveAndFlush(legacy);
        assertEquals("旧通用经验",memoryService.snapshot(next,"actor").get("approvedExperience"));
    }
    @Test void ordinaryObservationUsesActionKindAndKeepsItsPublicationUntilNextGame() throws Exception {
        var fixture=fixture("GENERAL");var card=maps(content(fixture.job,"GENERAL","说清判断依据。").get("cards")).getFirst();
        card.put("actionKinds",List.of("SPEAK"));
        var version=strategies.createVersion(fixture.experiment,fixture.match,"fixture:"+UUID.randomUUID(),Map.of("cards",List.of(card)),compatibility());
        publications.apply(request("PUBLISH",version.id,List.of("ai1")),"fixture-admin");
        var first=state();publications.freezeState(first);
        var selected=map(memoryService.snapshot(first,"actor","SPEAK").get("strategy"));
        assertEquals(version.id,selected.get("versionId"));
        assertTrue(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(selected).length()<=1200);
        assertFalse(memoryService.snapshot(first,"actor","VOTE").containsKey("strategy"));
        assertFalse(memoryService.snapshot(first,"actor").containsKey("strategy"));
        var factory=new com.aisocialgame.service.ai.v2.ObservationFactory(mock(PersonaRepository.class),memoryService);
        var ruleSet=mock(com.aisocialgame.engine.v2.GameRuleSet.class);
        when(ruleSet.observationRules(any())).thenReturn(rules);when(ruleSet.legalActions(any(),anyString())).thenReturn(List.of());
        when(ruleSet.privateData(any(),anyString())).thenReturn(Map.of());when(ruleSet.knowledge(any())).thenReturn(List.of());
        var observation=factory.build(first,ruleSet,new com.aisocialgame.engine.v2.TurnRequest("actor","SPEAK","one"));
        assertEquals(version.id,map(observation.memory().get("strategy")).get("versionId"));
        publications.apply(request("WITHDRAW",null,List.of("ai1")),"fixture-admin");publications.freezeState(first);
        assertEquals(version.id,map(memoryService.snapshot(first,"actor","SPEAK").get("strategy")).get("versionId"));
        var next=state();publications.freezeState(next);assertFalse(memoryService.snapshot(next,"actor","SPEAK").containsKey("strategy"));
    }

    @Test void labOnlyUsesExplicitFrozenStrategyAndNeverReadsPublishedOrLegacyExperience() {
        var version=version("GENERAL");publications.apply(request("PUBLISH",version.id,List.of("ai1")),"fixture-admin");
        var state=state();state.getData().put("lab",Map.of("experimentId",version.experimentId));
        state.getData().put("labPlayers",Map.of("actor",Map.of("strategySnapshot",Map.of(),"modelSelection",selection.toMap())));
        publications.freezeState(state);assertFalse(state.getData().containsKey(LabStrategyService.STATE_SNAPSHOTS));
        assertFalse(memoryService.snapshot(state,"actor").containsKey("approvedExperience"));assertFalse(memoryService.snapshot(state,"actor").containsKey("strategy"));
        state.getData().put("labPlayers",Map.of("actor",Map.of("strategySnapshot",strategies.strategySnapshot(version.id),"modelSelection",selection.toMap())));
        assertEquals(version.id,map(memoryService.snapshot(state,"actor").get("strategy")).get("versionId"));
    }

    @Test void rolePhaseRulesAndModelAllLimitSelectionWithoutChangingPersona() {
        var version=version("SEER");var snapshot=strategies.strategySnapshot(version.id);
        assertFalse(LabStrategyService.select(snapshot,"SEER","DAY_DISCUSS",rules,selection.toMap()).isEmpty());
        assertTrue(LabStrategyService.select(snapshot,"WEREWOLF","DAY_DISCUSS",rules,selection.toMap()).isEmpty());
        assertTrue(LabStrategyService.select(snapshot,"SEER","NIGHT",rules,selection.toMap()).isEmpty());
        var six=new LinkedHashMap<>(rules);six.put("playerCount",6);
        assertTrue(LabStrategyService.select(snapshot,"SEER","DAY_DISCUSS",six,selection.toMap()).isEmpty());
        assertTrue(LabStrategyService.select(snapshot,"SEER","DAY_DISCUSS",rules,AiModelSelection.defaults("other-model").toMap()).isEmpty());
    }

    @Test void sharedVersionRequiresActualVisibleEvidenceAndSameRequestNeverRepublishes() {
        var fixture=fixture("SEER");var content=content(fixture.job,"SEER","依据可见证据核对发言。");
        var version=strategies.createVersion(fixture.experiment,fixture.match,"fixture:"+UUID.randomUUID(),content,compatibility());
        assertEquals(version.id,strategies.createVersion(fixture.experiment,fixture.match,version.requestId,content,compatibility()).id);
        assertEquals(1,versions.findByExperimentIdOrderBySequenceNumberDesc(fixture.experiment.id).size());
        var badCard=maps(content(fixture.job,"SEER","改变策略。").get("cards")).getFirst();
        badCard.put("evidence",List.of(Map.of("jobId",fixture.job.getId(),"actorId",fixture.job.getActorId(),"eventIds",List.of("future-or-other-private-event"))));
        var invisible=Map.<String,Object>of("cards",List.of(badCard));
        assertThrows(ApiException.class,()->strategies.createVersion(fixture.experiment,fixture.match,"fixture:"+UUID.randomUUID(),invisible,compatibility()));
        assertEquals(version.id,fixture.experiment.configuration.get("strategyHeadId"));
    }

    @Test void unchangedCardsMayRetainHistoricalEvidenceButChangedCardsNeedCurrentMatchEvidence() {
        var first=fixture("SEER");var original=content(first.job,"SEER","依据可见证据核对发言。");
        var version=strategies.createVersion(first.experiment,first.match,"fixture:"+UUID.randomUUID(),original,compatibility());
        var next=new LabMatch();next.experimentId=first.experiment.id;next.sequenceNumber=2;next.roomId=UUID.randomUUID().toString();next.archiveId="archive-"+UUID.randomUUID();next.status="FINISHED";matches.saveAndFlush(next);
        var copied=strategies.createVersion(first.experiment,next,"fixture:"+UUID.randomUUID(),original,compatibility());assertEquals(version.id,copied.previousId);
        assertThrows(ApiException.class,()->strategies.createVersion(first.experiment,next,"fixture:"+UUID.randomUUID(),content(first.job,"SEER","不能用旧证据伪装本局新增经验。"),compatibility()));
    }

    @Test void malformedCardsCannotBePublishedAndMissingCompatibilityIsRejected() {
        var fixture=fixture("SEER");var invalid=content(fixture.job,"WEREWOLF","错误引用其他身份。");
        assertThrows(ApiException.class,()->strategies.createVersion(fixture.experiment,fixture.match,"fixture:"+UUID.randomUUID(),invalid,compatibility()));
        assertThrows(ApiException.class,()->LabStrategyService.validateContent(Map.of("cards",List.of(Map.of("advice","缺失字段")))));
        assertThrows(ApiException.class,()->LabStrategyService.validateCompatibility(Map.of("gameId","werewolf")));
        assertTrue(versions.findByExperimentIdOrderBySequenceNumberDesc(fixture.experiment.id).isEmpty());
    }

    @Test void allPhaseCardsAndCompleteRuntimeLimitAreDeterministic() throws Exception {
        var fixture=fixture("SEER");var card=maps(content(fixture.job,"GENERAL","依据可见证据核对发言。").get("cards")).getFirst();card.put("phases",List.of());
        var version=strategies.createVersion(fixture.experiment,fixture.match,"fixture:"+UUID.randomUUID(),Map.of("cards",List.of(card)),compatibility());
        var selected=LabStrategyService.select(strategies.strategySnapshot(version.id),"SEER","NIGHT",rules,selection.toMap());
        assertFalse(selected.isEmpty());assertTrue(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(selected).length()<=1200);
        assertEquals(selected,LabStrategyService.select(strategies.strategySnapshot(version.id),"SEER","NIGHT",rules,selection.toMap()));
        assertThrows(ApiException.class,()->strategies.createVersion(fixture.experiment,fixture.match,"fixture:"+UUID.randomUUID(),content(fixture.job,"SEER","证".repeat(1100)),compatibility()));
    }

    @Test void knownAssessmentFindingsBlockAllBindingsWhileUnknownAndClearedFindingsRemainTrialOnly() {
        var version=version("GENERAL");var originalContent=LabStrategyService.copy(version.content);
        var first=publications.apply(request("PUBLISH",version.id,List.of("ai1")),"fixture-admin");
        for(String finding:List.of("ruleError","evidenceLeak","severeContradiction")) {
            var saved=assessments.assess(version.experimentId,version.id,new LabStrategyAssessmentService.Request(
                    "ruleError".equals(finding),"evidenceLeak".equals(finding),"severeContradiction".equals(finding),"已核实："+finding),"reviewer");
            assertEquals(Boolean.TRUE,saved.get(finding));assertEquals("reviewer",saved.get("reviewedBy"));assertNotNull(saved.get("reviewedAt"));
            var error=assertThrows(ApiException.class,()->publications.apply(request("PUBLISH",version.id,List.of("ai2","ai3","ai4")),"fixture-admin"));
            assertEquals("STRATEGY_ASSESSMENT_BLOCKED",error.getCode());
        }
        assertEquals(1,receipts.count());assertEquals(1,publications.bindings().stream().filter(binding->binding.containsKey("strategyVersionId")).count());
        var cleared=assessments.assess(version.experimentId,version.id,new LabStrategyAssessmentService.Request(false,false,false,"人工复核已消除上述缺陷；效果仍待验证"),"reviewer");
        assertEquals(cleared,map(map(experiments.findById(version.experimentId).orElseThrow().configuration.get("strategyAssessments")).get(version.id)));
        var next=publications.apply(request("PUBLISH",version.id,List.of("ai2")),"fixture-admin");assertEquals("TRIAL",next.get("mode"));
        assertEquals(originalContent,versions.findById(version.id).orElseThrow().content);
        assertEquals(first.get("id"),publications.bindings().stream().filter(binding->"ai1".equals(binding.get("personaId"))).findFirst().orElseThrow().get("publicationId"));
    }

    @Test void assessmentRejectsAnotherExperimentsVersionAndMissingFindings() {
        var first=version("GENERAL");var other=version("GENERAL");
        assertThrows(ApiException.class,()->assessments.assess(first.experimentId,other.id,new LabStrategyAssessmentService.Request(false,false,false,""),"reviewer"));
        assertThrows(ApiException.class,()->assessments.assess(first.experimentId,first.id,new LabStrategyAssessmentService.Request(null,false,false,""),"reviewer"));
        assertFalse(experiments.findById(first.experimentId).orElseThrow().configuration.containsKey("strategyAssessments"));
    }

    @Test void optionalCounterEvidencePersistsAndInheritsWithoutEnteringRuntimeStrategy() {
        var fixture=fixture("SEER");var legacy=content(fixture.job,"SEER","依据可见证据核对发言。");
        assertFalse(maps(LabStrategyService.validateContent(legacy).get("cards")).getFirst().containsKey("counterEvidence"));
        var card=maps(legacy.get("cards")).getFirst();card.put("counterEvidence",List.of());
        assertEquals(List.of(),maps(LabStrategyService.validateContent(Map.of("cards",List.of(card))).get("cards")).getFirst().get("counterEvidence"));
        card.put("counterEvidence",List.of(Map.of("jobId",fixture.job.getId(),"actorId",fixture.job.getActorId(),"eventIds",List.of("visible-private-event"))));
        var content=Map.<String,Object>of("cards",List.of(card));
        var version=strategies.createVersion(fixture.experiment,fixture.match,"fixture:"+UUID.randomUUID(),content,compatibility());
        assertEquals(card,maps(version.content.get("cards")).getFirst());
        assertEquals(version.id,strategies.createVersion(fixture.experiment,fixture.match,version.requestId,content,compatibility()).id);
        var runtimeCard=maps(LabStrategyService.select(strategies.strategySnapshot(version.id),"SEER","DAY_DISCUSS",rules,selection.toMap()).get("cards")).getFirst();
        assertEquals(Set.of("id","role","condition","advice","exceptions"),runtimeCard.keySet());
        var next=new LabMatch();next.experimentId=fixture.experiment.id;next.sequenceNumber=2;next.roomId=UUID.randomUUID().toString();next.archiveId="archive-"+UUID.randomUUID();next.status="FINISHED";matches.saveAndFlush(next);
        var inherited=strategies.createVersion(fixture.experiment,next,"fixture:"+UUID.randomUUID(),content,compatibility());
        assertEquals(version.id,inherited.previousId);assertEquals(content,inherited.content);
        assertEquals(content,versions.findById(version.id).orElseThrow().content);
    }

    @Test void counterEvidenceRequiresSameMatchSuccessfulActorAndVisibleRoleEvidence() {
        var fixture=fixture("SEER");var counter=new AiTurnJob();counter.setId(UUID.randomUUID().toString());counter.setRoomId(fixture.match.roomId);
        counter.setInstanceId(fixture.match.archiveId);counter.setActorId("counter-actor");counter.setKind("SPEAK");counter.setTurnKey(UUID.randomUUID().toString());counter.setStatus("SUCCEEDED");
        counter.setObservation(Map.of("self",Map.of("role","SEER"),"events",List.of(Map.of("eventId","counter-visible"))));jobs.saveAndFlush(counter);
        var card=maps(content(fixture.job,"SEER","加入反例边界。").get("cards")).getFirst();
        for(var reference:List.of(Map.of("jobId",counter.getId(),"actorId","someone-else","eventIds",List.of("counter-visible")),
                Map.of("jobId",counter.getId(),"actorId",counter.getActorId(),"eventIds",List.of("other-private-or-future")))) {
            card.put("counterEvidence",List.of(reference));
            assertThrows(ApiException.class,()->strategies.createVersion(fixture.experiment,fixture.match,"fixture:"+UUID.randomUUID(),Map.of("cards",List.of(card)),compatibility()));
        }
        card.put("counterEvidence",List.of(Map.of("jobId",counter.getId(),"actorId",counter.getActorId(),"eventIds",List.of("counter-visible"))));
        counter.setStatus("FAILED");jobs.saveAndFlush(counter);
        assertThrows(ApiException.class,()->strategies.createVersion(fixture.experiment,fixture.match,"fixture:"+UUID.randomUUID(),Map.of("cards",List.of(card)),compatibility()));
        counter.setStatus("SUCCEEDED");counter.setObservation(Map.of("self",Map.of("role","WEREWOLF"),"events",List.of(Map.of("eventId","counter-visible"))));jobs.saveAndFlush(counter);
        assertThrows(ApiException.class,()->strategies.createVersion(fixture.experiment,fixture.match,"fixture:"+UUID.randomUUID(),Map.of("cards",List.of(card)),compatibility()));
        counter.setObservation(Map.of("self",Map.of("role","SEER"),"events",List.of(Map.of("eventId","counter-visible"))));counter.setInstanceId("other-match");jobs.saveAndFlush(counter);
        assertThrows(ApiException.class,()->strategies.createVersion(fixture.experiment,fixture.match,"fixture:"+UUID.randomUUID(),Map.of("cards",List.of(card)),compatibility()));
        assertTrue(versions.findByExperimentIdOrderBySequenceNumberDesc(fixture.experiment.id).isEmpty());
    }

    private StrategyPublicationService.Request request(String action,String version,List<String> targets) {return new StrategyPublicationService.Request(UUID.randomUUID().toString(),action,version,targets,"TRIAL");}
    private Map<String,Object> compatibility() {return Map.of("gameId","werewolf","rules",rules,"modelSelection",selection.toMap());}
    private LabStrategyVersion version(String role) {var f=fixture("GENERAL".equals(role)?"SEER":role);return strategies.createVersion(f.experiment,f.match,"fixture:"+UUID.randomUUID(),content(f.job,role,"依据可见证据核对发言。"),compatibility());}
    private record Fixture(LabExperiment experiment,LabMatch match,AiTurnJob job) {}
    private Fixture fixture(String role) {
        var experiment=new LabExperiment();experiment.name="strategy fixture";experiment.createdBy="fixture-admin";experiment.budgetPoints=3000;experiment.configuration.put("gameId","werewolf");experiments.saveAndFlush(experiment);
        var match=new LabMatch();match.experimentId=experiment.id;match.sequenceNumber=1;match.roomId=UUID.randomUUID().toString();match.archiveId="archive-"+UUID.randomUUID();match.status="FINISHED";matches.saveAndFlush(match);
        var job=new AiTurnJob();job.setId(UUID.randomUUID().toString());job.setRoomId(match.roomId);job.setInstanceId(match.archiveId);job.setActorId("actor");job.setKind("SPEAK");job.setTurnKey(UUID.randomUUID().toString());job.setStatus("SUCCEEDED");
        job.setObservation(Map.of("self",Map.of("role",role),"events",List.of(Map.of("eventId","visible-private-event","visibility","PRIVATE"))));jobs.saveAndFlush(job);return new Fixture(experiment,match,job);
    }
    private Map<String,Object> content(AiTurnJob job,String role,String advice) {
        var evidence=new LinkedHashMap<String,Object>(Map.of("jobId",job.getId(),"actorId",job.getActorId(),"eventIds",List.of("visible-private-event")));
        return LabStrategyService.copy(Map.of("cards",List.of(Map.of("id","check_claims","role",role,"phases",List.of("DAY_DISCUSS"),"condition","讨论身份和站位时", "advice",advice,"exceptions","证据不足时保留不确定性", "evidence",List.of(evidence)))));
    }
    private GameState state() {
        var state=new GameState();state.setGameId("werewolf");state.setRoomId(UUID.randomUUID().toString());state.setPhase("DAY_DISCUSS");
        state.getData().put("rules",new LinkedHashMap<>(rules));var player=new GamePlayerState();player.setPlayerId("actor");player.setPersonaId("ai1");player.setRole("SEER");player.setAi(true);state.setPlayers(List.of(player));return state;
    }
}
