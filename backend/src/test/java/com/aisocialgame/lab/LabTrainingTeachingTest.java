package com.aisocialgame.lab;

import com.aisocialgame.exception.ApiException;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.aisocialgame.engine.v2.RuleSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class LabTrainingTeachingTest {
    @Test void reasonableAndLegalDeceptionCanKeepOriginalActionWithoutInventingExperience() {
        for(String category:List.of("REASONABLE","LEGAL_DECEPTION","INSUFFICIENT_EVIDENCE","INPUT_GAP","SYSTEM_FAILURE")) {
            var result=LabTrainingTeaching.validateTeach(teach(category,List.of()),item("one"),Map.of(),"task");
            assertTrue(maps(result.get("changes")).isEmpty());assertEquals(category,map(result.get("diagnosis")).get("category"));
        }
        var normalized=LabTrainingTeaching.validateTeach(teach("RULE_UNDERSTANDING",List.of(change("ADD",card("board","GENERAL","先核对本桌角色配置")))),item("one"),Map.of(),"teach-task");
        var evidence=maps(map(maps(normalized.get("changes")).getFirst().get("card")).get("evidence")).getFirst();
        assertEquals(Map.of("caseId","one","taskId","teach-task","actorId","actor-one","evidenceIds",List.of("RULE:roleCounts")),evidence);
    }

    @Test void changesRequireVisibleEvidenceMatchingIdentityAndAtMostTwoUpdates() {
        var valid=change("ADD",card("board","GENERAL","核对角色"));
        var holdout=item("held-out");holdout.purpose="HOLDOUT";
        assertThrows(ApiException.class,()->LabTrainingTeaching.validateTeach(teach("RULE_UNDERSTANDING",List.of(valid)),holdout,Map.of(),"task"));
        assertThrows(ApiException.class,()->LabTrainingTeaching.validateTeach(teach("INPUT_GAP",List.of(valid)),item("one"),Map.of(),"task"));
        assertThrows(ApiException.class,()->LabTrainingTeaching.validateTeach(teach("RULE_UNDERSTANDING",List.of(change("ADD",card("seer","SEER","使用查验")))),item("one"),Map.of(),"task"));
        var forged=new LinkedHashMap<>(valid);forged.put("evidenceIds",List.of("other-actor-private-event"));
        assertThrows(ApiException.class,()->LabTrainingTeaching.validateTeach(teach("RULE_UNDERSTANDING",List.of(forged)),item("one"),Map.of(),"task"));
        assertThrows(ApiException.class,()->LabTrainingTeaching.validateTeach(teach("RULE_UNDERSTANDING",List.of(valid,valid,valid)),item("one"),Map.of(),"task"));
    }

    @Test void retirementCannotModifyUnobservedIdentityOrIgnoreBaseVersion() {
        var base=base(List.of(card("seer","SEER","查验建议"),card("general","GENERAL","通用建议")));
        assertThrows(ApiException.class,()->LabTrainingTeaching.validateTeach(teach("RULE_UNDERSTANDING",List.of(retire("seer"))),item("one"),base,"task"));
        assertThrows(ApiException.class,()->LabTrainingTeaching.validateTeach(teach("RULE_UNDERSTANDING",List.of(change("UPDATE",card("seer","GENERAL","更换身份也不能绕过")))),item("one"),base,"task"));
        assertThrows(ApiException.class,()->LabTrainingTeaching.validateTeach(teach("RULE_UNDERSTANDING",List.of(change("ADD",card("general","GENERAL","重复新增")))),item("one"),base,"task"));
        assertThrows(ApiException.class,()->LabTrainingTeaching.validateTeach(teach("RULE_UNDERSTANDING",List.of(retire("missing"))),item("one"),base,"task"));
        assertEquals(1,maps(LabTrainingTeaching.validateTeach(teach("RULE_UNDERSTANDING",List.of(retire("general"))),item("one"),base,"task").get("changes")).size());
    }

    @Test void judgeRequiresTwoTypedAnonymousResponsesWithConsistentCountsAndOwnEvidence() {
        var item=item("one");item.purpose="HOLDOUT";var valid=Map.<String,Object>of("assessments",List.of(rating("A",1,false,false),rating("B",0,false,false)));
        assertThrows(ApiException.class,()->LabTrainingTeaching.validateJudge(valid,item("development")));
        assertEquals(valid,LabTrainingTeaching.validateJudge(valid,item));
        assertThrows(ApiException.class,()->LabTrainingTeaching.validateJudge(Map.of("assessments",Arrays.asList(null,null)),item));
        assertThrows(ApiException.class,()->LabTrainingTeaching.validateJudge(Map.of("assessments",List.of(rating("A",0,false,false),rating("A",0,false,false))),item));
        var wrongCount=rating("B",1,false,false);wrongCount.put("targetErrors",0);
        assertThrows(ApiException.class,()->LabTrainingTeaching.validateJudge(Map.of("assessments",List.of(rating("A",0,false,false),wrongCount)),item));
        var wrongSource=rating("B",0,false,false);wrongSource.put("evidenceIds",List.of("RULE:unseen-board"));
        assertThrows(ApiException.class,()->LabTrainingTeaching.validateJudge(Map.of("assessments",List.of(rating("A",0,false,false),wrongSource)),item));
    }

    @Test void deterministicMergePreservesUntouchedCardsCombinesIdenticalEvidenceAndLeavesConflictsUnapplied() {
        var untouched=card("untouched","VILLAGER","保持原建议");var original=card("conflict","GENERAL","原始建议");var base=base(List.of(untouched,original));
        var first=teachingTask("first",teach("RULE_UNDERSTANDING",List.of(change("ADD",card("shared","GENERAL","核对本桌角色")))),Map.of());
        var second=teachingTask("second",teach("RULE_UNDERSTANDING",List.of(change("ADD",card("shared","GENERAL","核对本桌角色")))),Map.of());
        var left=teachingTask("left",teach("RULE_UNDERSTANDING",List.of(change("UPDATE",card("conflict","GENERAL","先等待证据")))),base);
        var right=teachingTask("right",teach("RULE_UNDERSTANDING",List.of(change("UPDATE",card("conflict","GENERAL","立即作出判断")))),base);
        var failed=teachingTask("failed",teach("RULE_UNDERSTANDING",List.of(change("ADD",card("ignored","GENERAL","未完成任务")))),Map.of());failed.status="FAILED";
        var merged=LabTrainingTeaching.merge(base,List.of(first,second,left,right,failed));
        var cards=new LinkedHashMap<String,Map<String,Object>>();maps(merged.content().get("cards")).forEach(c->cards.put(text(c.get("id")),c));
        assertEquals(untouched,cards.get("untouched"));assertEquals(original,cards.get("conflict"));assertFalse(cards.containsKey("ignored"));
        assertEquals(2,maps(cards.get("shared").get("evidence")).size());assertEquals(1,merged.applied());assertEquals(1,merged.conflicts().size());
        assertEquals("conflict",merged.conflicts().getFirst().get("cardId"));assertEquals(List.of(untouched,original),map(base.get("content")).get("cards"));
    }

    @Test void completeBlindPairsReportImprovementByEightIndependentSourcesRegardlessOfLabelOrder() {
        var fixture=fixture();var assessment=LabTrainingTeaching.assess(fixture.tasks(),fixture.cases());
        assertEquals("IMPROVED",assessment.get("status"));assertEquals(32,assessment.get("completedPairs"));assertEquals(8,assessment.get("independentSourceGroups"));
        assertEquals(32,assessment.get("baselineErrors"));assertEquals(0,assessment.get("candidateErrors"));assertEquals(false,assessment.get("publicationBlocked"));
        assertEquals(8,strings(assessment.get("improvedSourceGroups")).size());
    }

    @Test void existingCandidateRuleOrVisibilityErrorsStillBlockPublicationEvenWithoutNewErrors() {
        var fixture=fixture();setRatings(fixture.tasks().getFirst(),rating("",2,true,true),rating("",2,true,true));
        var result=LabTrainingTeaching.assess(fixture.tasks(),fixture.cases());
        assertEquals("IMPROVED",result.get("status"));assertEquals(0,result.get("newRuleErrors"));assertEquals(0,result.get("newEvidenceLeaks"));
        assertEquals(1,result.get("candidateRuleErrors"));assertEquals(1,result.get("candidateEvidenceLeaks"));assertEquals(true,result.get("publicationBlocked"));
    }

    @Test void unknownFailedMissingMappingAndDuplicateCaseNeverCountAsCompletedAcceptance() {
        var unknown=fixture();var b=rating("",1,false,false);b.put("facts","UNSCORED");b.put("targetErrors",0);setRatings(unknown.tasks().getFirst(),b,rating("",0,false,false));
        assertEquals("INCOMPLETE",LabTrainingTeaching.assess(unknown.tasks(),unknown.cases()).get("status"));
        var failed=fixture();failed.tasks().getFirst().status="FAILED";assertEquals("INCOMPLETE",LabTrainingTeaching.assess(failed.tasks(),failed.cases()).get("status"));
        var missing=fixture();missing.tasks().getFirst().result.remove("variantByLabel");var result=LabTrainingTeaching.assess(missing.tasks(),missing.cases());
        assertEquals("INCOMPLETE",result.get("status"));assertEquals(1,result.get("invalidPairs"));assertEquals(31,result.get("completedPairs"));
        var duplicate=fixture();duplicate.tasks().get(1).caseId=duplicate.tasks().getFirst().caseId;
        assertEquals("INCOMPLETE",LabTrainingTeaching.assess(duplicate.tasks(),duplicate.cases()).get("status"));
    }

    @Test void aNewRuleErrorIsRegressionEvenIfAggregateTargetErrorsFall() {
        var fixture=fixture();setRatings(fixture.tasks().getFirst(),rating("",1,false,false),rating("",1,true,false));
        var assessment=LabTrainingTeaching.assess(fixture.tasks(),fixture.cases());assertEquals("REGRESSION",assessment.get("status"));assertEquals(1,assessment.get("newRuleErrors"));
        assertTrue((Integer)assessment.get("candidateErrors")<(Integer)assessment.get("baselineErrors"));assertEquals(true,assessment.get("publicationBlocked"));
    }

    private LabTrainingCase item(String id) {
        var item=new LabTrainingCase();item.id=id;item.purpose="DEVELOPMENT";item.sourceGroup="source";item.personaId="ai1";
        item.data=Map.of("observation",Map.of("actorId","actor-"+id,"self",Map.of("role","VILLAGER")),"validEvidenceIds",List.of("RULE:roleCounts"));return item;
    }
    private Map<String,Object> card(String id,String role,String advice) {
        var card=new LinkedHashMap<String,Object>();card.put("id",id);card.put("role",role);card.put("phases",List.of());card.put("actionKinds",List.of());card.put("priority",80);
        card.put("condition","判断身份前");card.put("advice",advice);card.put("exceptions","");card.put("evidence",List.of(Map.of("jobId","old-job","actorId","actor","eventIds",List.of("event"))));return card;
    }
    private Map<String,Object> change(String operation,Map<String,Object> card) {
        var candidate=new LinkedHashMap<>(card);candidate.remove("evidence");
        return Map.of("operation",operation,"card",candidate,"evidenceIds",List.of("RULE:roleCounts"),"reason","本桌角色配置有明确依据");
    }
    private Map<String,Object> retire(String id) {return Map.of("operation","RETIRE","cardId",id,"evidenceIds",List.of("RULE:roleCounts"),"reason","旧建议不适用于当前已核实规则");}
    private Map<String,Object> teach(String category,List<Map<String,Object>> changes) {return Map.of("diagnosis",Map.of("category",category,"summary","依据当前可见材料判断","evidenceIds",List.of("RULE:roleCounts")),"changes",changes);}
    private Map<String,Object> base(List<Map<String,Object>> cards) {return Map.of("content",Map.of("cards",cards));}
    private LabTrainingTask teachingTask(String id,Map<String,Object> output,Map<String,Object> base) {
        var task=new LabTrainingTask();task.id=id;task.caseId=id;task.kind="TEACH";task.status="SUCCEEDED";task.result=LabTrainingTeaching.validateTeach(output,item(id),base,id);return task;
    }
    private Map<String,Object> rating(String label,int errors,boolean rule,boolean leak) {
        var row=new LinkedHashMap<String,Object>();row.put("label",label);row.put("facts",errors>0?"FAIL":"PASS");row.put("evidence",errors>1?"FAIL":"PASS");row.put("response",errors>2?"FAIL":"PASS");row.put("targetErrors",errors);
        row.put("ruleError",rule);row.put("evidenceLeak",leak);row.put("evidenceIds",List.of("RULE:roleCounts"));row.put("reason","依据给出的本桌配置与匿名回答");return row;
    }
    private record Fixture(List<LabTrainingTask> tasks,Map<String,LabTrainingCase> cases) {}
    private Fixture fixture() {
        var cases=new LinkedHashMap<String,LabTrainingCase>();var tasks=new ArrayList<LabTrainingTask>();
        for(int i=0;i<32;i++) {
            var item=item("case-"+i);item.purpose="HOLDOUT";item.sourceGroup="template-"+(i/4);item.personaId="ai"+(i%4+1);cases.put(item.id,item);
            var task=new LabTrainingTask();task.caseId=item.id;task.kind="JUDGE";task.status="SUCCEEDED";
            task.result.put("variantByLabel",i%2==0?Map.of("A","BASELINE","B","CANDIDATE"):Map.of("A","CANDIDATE","B","BASELINE"));
            setRatings(task,rating("",1,false,false),rating("",0,false,false));tasks.add(task);
        }
        return new Fixture(tasks,cases);
    }
    private void setRatings(LabTrainingTask task,Map<String,Object> baseline,Map<String,Object> candidate) {
        var mapping=map(task.result.get("variantByLabel"));var rows=new ArrayList<Map<String,Object>>();
        for(String label:List.of("A","B")) {var row=new LinkedHashMap<>("BASELINE".equals(mapping.get(label))?baseline:candidate);row.put("label",label);rows.add(row);}
        task.result.put("assessments",rows);
    }
}
