package com.aisocialgame.lab;

import com.aisocialgame.exception.ApiException;
import org.springframework.http.HttpStatus;
import java.util.*;
import static com.aisocialgame.engine.v2.RuleSupport.*;

/** Small, evidence-bound teaching and blind assessment; no model-generated global rewrite. */
public final class LabTrainingTeaching {
    private LabTrainingTeaching() {}
    public static final String TEACH_SYSTEM="""
        你是九人标准狼人杀训练教练。本次只解决一个决策案例的规则与证据理解问题。
        输入用可逆字典表示重复结构，按referenceGuide还原。observation是当时实际可见输入，sourceAction是历史行动，practice是本次练习。
        actualPromptStatus=MISSING时不能推测缺失原提示。outcomeTruth是事后信息，不得当成玩家当时知道的事实。
        严格核对本桌roles/rules/knowledge，不因为通用知识提到守卫就认定本桌有守卫。区分事实、声明、猜测、否定和假设。
        狼人有意伪装是合法策略，不能仅因声称身份与实际不同判为幻觉。输入缺失、协议或网络故障不能教成应弃票。
        只使用validEvidenceIds中的引用。每条诊断及修改必须有支持证据。previousCards是相关旧卡，未涉及旧卡由程序原样保留。
        每次最多两项修改，允许没有可靠更新。不要生成固定发言模板，不记录座号和玩家姓名的固定身份。
        新增ADD必须使用新ASCII id；修改UPDATE或建议停用RETIRE必须用旧卡原id。priority为0到100整数，越大越优先。
        card.role为GENERAL/WEREWOLF/VILLAGER/SEER/WITCH/HUNTER；phases和actionKinds为空表示不限；不凭空生成未触发身份的策略。
        只返回JSON：{"diagnosis":{"category":"RULE_UNDERSTANDING","summary":"简短诊断","evidenceIds":["有效引用"]},"changes":[{"operation":"ADD","card":{"id":"ascii_id","role":"GENERAL","phases":[],"actionKinds":[],"priority":80,"condition":"适用情况","advice":"简短行动建议","exceptions":"例外"},"evidenceIds":["有效引用"],"reason":"修改依据"}]}。
        category只允许STRATEGY_ERROR/RULE_UNDERSTANDING/REASONABLE/INPUT_GAP/SYSTEM_FAILURE/LEGAL_DECEPTION/INSUFFICIENT_EVIDENCE。
        RETIRE不用card，改为cardId；无可靠更新返回changes:[]。不输出Markdown或内部思考。
        """;
    public static final String JUDGE_SYSTEM="""
        你是狼人杀局面评审。本次比较匿名且顺序随机的A、B两份回答，不知道哪份使用了候选策略。
        输入用可逆字典表示重复结构，按referenceGuide还原。只按玩家当时observation及caseRubric评判，事后真相不能倒灌。
        依据本桌角色与规则区分事实、身份声明、猜测、否定和假设。狼人有意伪装合法，不能只因身份声明不真实判错。
        对facts(规则与事实)、evidence(可见证据)、response(是否回应当前问题)分别给PASS/FAIL/UNSCORED。
        无法判断必须UNSCORED，不能当通过。每份结论必须引用validEvidenceIds中的证据并解释，评分不得依赖未给出的历史。
        ruleError表示明确违背本桌规则，evidenceLeak表示使用玩家当时不可见的信息；不能因狼人合法说谎设置这两项。
        targetErrors必须等于这三个分项中FAIL的数量。不要生成经验或策略修改。
        仅返回JSON：{"assessments":[{"label":"A","facts":"PASS","evidence":"PASS","response":"PASS","targetErrors":0,"ruleError":false,"evidenceLeak":false,"evidenceIds":["有效引用"],"reason":"简短依据"},{"label":"B","facts":"PASS","evidence":"PASS","response":"PASS","targetErrors":0,"ruleError":false,"evidenceLeak":false,"evidenceIds":["有效引用"],"reason":"简短依据"}]}。
        """;
    public static Map<String,Object> validateTeach(Map<String,Object> output,LabTrainingCase item,Map<String,Object> base,String taskId) {
        check("DEVELOPMENT".equals(item.purpose),"只有开发案例可以生成教学修改");
        check(output.keySet().equals(Set.of("diagnosis","changes")),"教学输出字段错误");
        var diagnosis=map(output.get("diagnosis"));String category=text(diagnosis.get("category"));
        check(diagnosis.keySet().equals(Set.of("category","summary","evidenceIds")),"诊断字段错误");
        check(Set.of("STRATEGY_ERROR","RULE_UNDERSTANDING","REASONABLE","INPUT_GAP","SYSTEM_FAILURE","LEGAL_DECEPTION","INSUFFICIENT_EVIDENCE").contains(category),"诊断类别无效");
        check(!text(diagnosis.get("summary")).isBlank(),"诊断缺少说明");references(diagnosis.get("evidenceIds"),item);
        check(output.get("changes") instanceof List<?> list && list.size()<=2 && list.stream().allMatch(Map.class::isInstance),"每案例最多两项策略修改");
        var changes=maps(output.get("changes"));
        if(Set.of("INPUT_GAP","SYSTEM_FAILURE","INSUFFICIENT_EVIDENCE").contains(category))check(changes.isEmpty(),"系统或输入缺口不能生成策略修改");
        var old=new HashMap<String,Map<String,Object>>();maps(map(base.get("content")).get("cards")).forEach(card->old.put(text(card.get("id")),card));
        var used=new HashSet<String>();var normalized=new ArrayList<Map<String,Object>>();
        for(var change:changes) {
            String operation=text(change.get("operation"));check(Set.of("ADD","UPDATE","RETIRE").contains(operation),"修改操作无效");
            check(change.keySet().equals(Set.of("operation","RETIRE".equals(operation)?"cardId":"card","evidenceIds","reason")),"策略修改字段错误");
            references(change.get("evidenceIds"),item);check(!text(change.get("reason")).isBlank(),"策略修改缺少原因");
            var row=new LinkedHashMap<>(change);var card=new LinkedHashMap<>(map(change.get("card")));
            String id="RETIRE".equals(operation)?text(change.get("cardId")):text(card.get("id"));check(used.add(id),"同一任务重复修改一条策略");
            check("ADD".equals(operation)?!old.containsKey(id):old.containsKey(id),"策略修改与基础版本不匹配");
            var observation=map(item.data.get("observation"));
            if(!"ADD".equals(operation))check("GENERAL".equals(old.get(id).get("role"))
                    || Objects.equals(map(observation.get("self")).get("role"),old.get(id).get("role")),"不能修改或停用未发生身份的策略");
            if(!"RETIRE".equals(operation)) {
                check("GENERAL".equals(card.get("role")) || Objects.equals(map(observation.get("self")).get("role"),card.get("role")),"不能推导未发生的身份策略");
                card.put("evidence",List.of(Map.of("caseId",item.id,"taskId",taskId,"actorId",observation.get("actorId"),"evidenceIds",strings(change.get("evidenceIds")))));
                card= new LinkedHashMap<>(maps(LabStrategyService.validateContent(Map.of("cards",List.of(card))).get("cards")).getFirst());row.put("card",card);
            }
            normalized.add(row);
        }
        return Map.of("diagnosis",diagnosis,"changes",normalized);
    }
    public static Map<String,Object> validateJudge(Map<String,Object> output,LabTrainingCase item) {
        check("HOLDOUT".equals(item.purpose),"独立评审必须使用保留题");
        check(output.keySet().equals(Set.of("assessments")) && output.get("assessments") instanceof List<?> list
                && list.size()==2 && list.stream().allMatch(Map.class::isInstance),"评审必须包含A和B两份结果");
        var labels=new HashSet<String>();
        for(var row:maps(output.get("assessments"))) {
            check(row.keySet().equals(Set.of("label","facts","evidence","response","targetErrors","ruleError","evidenceLeak","evidenceIds","reason")),"评审分项字段错误");
            check(Set.of("A","B").contains(text(row.get("label"))) && labels.add(text(row.get("label"))),"匿名评审标签错误");
            int errors=0;
            for(String field:List.of("facts","evidence","response")) {check(Set.of("PASS","FAIL","UNSCORED").contains(text(row.get(field))),"评审分项无效");if("FAIL".equals(row.get(field)))errors++;}
            check(row.get("targetErrors") instanceof Number n && n.doubleValue()==errors,"目标错误数与分项不一致");
            check(row.get("ruleError") instanceof Boolean && row.get("evidenceLeak") instanceof Boolean,"评审缺少明确规则与视角判断");
            check(!text(row.get("reason")).isBlank(),"评审缺少依据说明");references(row.get("evidenceIds"),item);
        }
        return output;
    }
    private static void references(Object ids,LabTrainingCase item) {
        check(ids instanceof List<?> list && !list.isEmpty() && list.stream().allMatch(String.class::isInstance),"需要非空案例证据引用");
        check(new HashSet<>(strings(item.data.get("validEvidenceIds"))).containsAll(strings(ids)),"引用不属于该案例当时可见的材料");
    }
    public record Merge(Map<String,Object> content,List<Map<String,Object>> conflicts,int applied) {}
    public static Merge merge(Map<String,Object> base,List<LabTrainingTask> tasks) {
        var cards=new LinkedHashMap<String,Map<String,Object>>();maps(map(base.get("content")).get("cards")).forEach(card->cards.put(text(card.get("id")),card));
        var byId=new LinkedHashMap<String,List<Map<String,Object>>>();
        for(var task:tasks)if("TEACH".equals(task.kind) && "SUCCEEDED".equals(task.status))for(var change:maps(task.result.get("changes"))) {
            String id="RETIRE".equals(change.get("operation"))?text(change.get("cardId")):text(map(change.get("card")).get("id"));
            byId.computeIfAbsent(id,key->new ArrayList<>()).add(change);
        }
        var conflicts=new ArrayList<Map<String,Object>>();int applied=0;
        for(var entry:byId.entrySet()) {
            var proposed=entry.getValue();var first=proposed.getFirst();
            var fingerprints=proposed.stream().map(change->{var card=new LinkedHashMap<>(map(change.get("card")));card.remove("evidence");return LabStrategyService.hash(Map.of("operation",change.get("operation"),"card",card));}).distinct().toList();
            if(fingerprints.size()>1) {conflicts.add(Map.of("cardId",entry.getKey(),"changes",proposed));continue;}
            if("RETIRE".equals(first.get("operation")))cards.remove(entry.getKey());
            else {
                var card=new LinkedHashMap<>(map(first.get("card")));var evidence=new ArrayList<Map<String,Object>>();
                proposed.forEach(change->evidence.addAll(maps(map(change.get("card")).get("evidence"))));card.put("evidence",evidence);cards.put(entry.getKey(),card);
            }
            applied++;
        }
        return new Merge(Map.of("cards",List.copyOf(cards.values())),conflicts,applied);
    }
    public static Map<String,Object> assess(List<LabTrainingTask> tasks,Map<String,LabTrainingCase> cases) {
        var judges=tasks.stream().filter(t->"JUDGE".equals(t.kind)).toList();int baseline=0,candidate=0,newRules=0,newLeaks=0,complete=0,candidateRules=0,candidateLeaks=0,invalid=0;
        var groups=new LinkedHashMap<String,int[]>();var groupPersonas=new HashMap<String,Set<String>>();var seen=new HashSet<String>();boolean unscored=false;
        for(var task:judges) {
            if(!"SUCCEEDED".equals(task.status))continue;
            var item=cases.get(task.caseId);var mapping=map(task.result.get("variantByLabel"));
            if(item==null || !"HOLDOUT".equals(item.purpose) || text(item.sourceGroup).isBlank() || text(item.personaId).isBlank()
                    || seen.contains(task.caseId) || !mapping.keySet().equals(Set.of("A","B"))
                    || !new HashSet<>(mapping.values()).equals(Set.of("BASELINE","CANDIDATE"))) {invalid++;continue;}
            try {
                var output=new LinkedHashMap<String,Object>();output.put("assessments",task.result.get("assessments"));validateJudge(output,item);
            } catch(ApiException malformed) {invalid++;continue;}
            seen.add(task.caseId);complete++;
            Map<String,Object> b=Map.of(),c=Map.of();
            for(var row:maps(task.result.get("assessments"))) {
                if(List.of("facts","evidence","response").stream().anyMatch(key->"UNSCORED".equals(row.get(key))))unscored=true;
                if("BASELINE".equals(mapping.get(text(row.get("label")))))b=row;else c=row;
            }
            int be=number(b.get("targetErrors"),0),ce=number(c.get("targetErrors"),0);baseline+=be;candidate+=ce;
            if(flag(c.get("ruleError")) && !flag(b.get("ruleError")))newRules++;
            if(flag(c.get("evidenceLeak")) && !flag(b.get("evidenceLeak")))newLeaks++;
            if(flag(c.get("ruleError")))candidateRules++;
            if(flag(c.get("evidenceLeak")))candidateLeaks++;
            var counts=groups.computeIfAbsent(item.sourceGroup,key->new int[2]);counts[0]+=be;counts[1]+=ce;
            groupPersonas.computeIfAbsent(item.sourceGroup,key->new HashSet<>()).add(item.personaId);
        }
        var improved=groups.entrySet().stream().filter(e->e.getValue()[1]<e.getValue()[0]).map(Map.Entry::getKey).toList();
        boolean full=judges.size()==32 && complete==32 && !unscored && invalid==0 && groups.size()==8
                && groupPersonas.values().stream().allMatch(personas->personas.size()==4);
        String status=!full?"INCOMPLETE":newRules>0 || newLeaks>0 || candidate>baseline?"REGRESSION":candidate<baseline&&!improved.isEmpty()?"IMPROVED":"NO_IMPROVEMENT";
        var result=new LinkedHashMap<String,Object>();result.put("status",status);result.put("completedPairs",complete);result.put("plannedPairs",32);
        result.put("baselineErrors",baseline);result.put("candidateErrors",candidate);result.put("improvedSourceGroups",improved);
        result.put("newRuleErrors",newRules);result.put("newEvidenceLeaks",newLeaks);result.put("hasUnscored",unscored||complete<32);
        result.put("unscoredPairs",32-complete);
        result.put("candidateRuleErrors",candidateRules);result.put("candidateEvidenceLeaks",candidateLeaks);
        result.put("publicationBlocked",candidateRules>0 || candidateLeaks>0);result.put("invalidPairs",invalid);
        result.put("independentSourceGroups",groups.size());result.put("effect","PILOT_MODEL_JUDGED");return result;
    }
    private static void check(boolean value,String message) {if(!value)throw new ApiException(HttpStatus.BAD_REQUEST,message,"TRAINING_OUTPUT_INVALID",Map.of());}
}
