package com.aisocialgame.lab;

import com.aisocialgame.exception.ApiException;
import com.aisocialgame.repository.AiTurnJobRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static com.aisocialgame.engine.v2.RuleSupport.*;

@Service
public class LabStrategyService {
    public static final Set<String> ROLES=Set.of("GENERAL","WEREWOLF","VILLAGER","SEER","WITCH","HUNTER");
    public static final Set<String> PHASES=Set.of("NIGHT","DAY_DISCUSS","DAY_INTERACTION","DAY_VOTE","DEATH_ACTION","LAST_WORDS");
    public static final String STATE_SNAPSHOTS="werewolfStrategySnapshots";
    private static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();
    private final LabStrategyVersionRepository versions;
    private final LabExperimentRepository experiments;
    private final AiTurnJobRepository jobs;
    @org.springframework.beans.factory.annotation.Autowired private LabTrainingCaseRepository trainingCases;
    @org.springframework.beans.factory.annotation.Autowired private LabTrainingTaskRepository trainingTasks;
    public LabStrategyService(LabStrategyVersionRepository versions,LabExperimentRepository experiments,AiTurnJobRepository jobs) {
        this.versions=versions;this.experiments=experiments;this.jobs=jobs;
    }

    /** The caller holds the experiment and match locks; the version and training head commit together. */
    @Transactional
    public LabStrategyVersion createVersion(LabExperiment experiment,LabMatch match,String requestId,
                                             Map<String,Object> content,Map<String,Object> compatibility) {
        var normalized=validateContent(content);var compatible=validateCompatibility(compatibility);
        String fingerprint=hash(Map.of("content",normalized,"compatibility",compatible));
        var sizeSnapshot=Map.<String,Object>of("id",UUID.randomUUID().toString(),"contentHash",fingerprint,"compatibility",compatible);
        for(var card:maps(normalized.get("cards")))
            require(encoded(runtimeSelection(sizeSnapshot,List.of(runtimeCard(card)))).length()<=1200,"单张策略卡及必要元数据超过运行注入上限");
        var existing=versions.findByRequestId(requestId).orElse(null);
        if(existing!=null) {
            require(existing.experimentId.equals(experiment.id) && existing.contentHash.equals(fingerprint),"原请求已发布不同的策略内容");
            return existing;
        }
        require(experiment.id.equals(match.experimentId) && match.evaluationId==null,"评测不能发布训练策略");
        require("werewolf".equals(experiment.configuration.get("gameId")),"仅狼人杀实验使用共享策略");
        require(Set.of("FINISHED","REVIEWING","REVIEWED").contains(match.status) && match.reason==null && match.archiveId!=null,"对局尚未正常结束");
        require(requestId!=null && !requestId.isBlank() && requestId.length()<=128,"策略请求身份无效");
        String previous=text(experiment.configuration.get("strategyHeadId"));
        var previousContent=previous.isBlank()?Map.<String,Object>of():requireVersion(previous).content;
        validateEvidence(normalized,previousContent,match);
        var version=new LabStrategyVersion();version.experimentId=experiment.id;version.requestId=requestId;
        version.sequenceNumber=versions.latestSequence(experiment.id)+1;version.previousId=previous.isBlank()?null:previous;
        version.content=normalized;version.compatibility=compatible;version.contentHash=fingerprint;
        version.evidence=Map.of("matchId",match.id,"archiveId",match.archiveId,"requestId",requestId);
        versions.saveAndFlush(version);experiment.configuration.put("strategyHeadId",version.id);experiments.save(experiment);
        return version;
    }

    public void validateEvidence(Map<String,Object> content,Map<String,Object> previousContent,LabMatch match) {
        var previous=new HashMap<String,Map<String,Object>>();
        maps(previousContent.get("cards")).forEach(card->previous.put(text(card.get("id")),card));
        for(var card:maps(content.get("cards"))) {
            if(card.equals(previous.get(text(card.get("id"))))) continue;
            for(String source:List.of("evidence","counterEvidence")) for(var reference:maps(card.get(source))) {
                var job=jobs.findById(text(reference.get("jobId"))).orElseThrow(()->invalid("策略证据任务不存在"));
                require(match.archiveId.equals(job.getInstanceId()) && match.roomId.equals(job.getRoomId()),"新策略必须引用本局决策证据");
                require(job.getActorId().equals(reference.get("actorId")) && "SUCCEEDED".equals(job.getStatus()),"策略证据必须属于实际完成行动的角色");
                var observation=job.getObservation();
                require("GENERAL".equals(card.get("role")) || card.get("role").equals(map(observation.get("self")).get("role")),"身份策略证据的角色不匹配");
                Set<String> visible=new HashSet<>();maps(observation.get("events")).forEach(event->visible.add(text(event.get("eventId"))));
                require(visible.containsAll(strings(reference.get("eventIds"))),"策略引用了决策当时不可见的事件");
            }
        }
    }

    /** Case learning publishes a real course version, without inventing a completed match. */
    @Transactional
    public LabStrategyVersion createTrainingVersion(LabExperiment experiment,LabTrainingRun run,Map<String,Object> content) {
        require(experiment.id.equals(run.experimentId),"课程不属于实验");
        var normalized=content.keySet().equals(Set.of("cards")) && content.get("cards") instanceof List<?> list && list.isEmpty()
                ?Map.<String,Object>of("cards",List.of()):validateContent(content);var base=map(run.configuration.get("baseStrategy"));
        var old=new HashMap<String,Map<String,Object>>();maps(map(base.get("content")).get("cards")).forEach(card->old.put(text(card.get("id")),card));
        for(var card:maps(normalized.get("cards"))) {
            if(card.equals(old.get(text(card.get("id")))))continue;
            for(String field:List.of("evidence","counterEvidence"))for(var reference:maps(card.get(field))) {
                var item=trainingCases.findById(text(reference.get("caseId"))).orElseThrow(()->invalid("课程证据案例不存在"));
                var task=trainingTasks.findById(text(reference.get("taskId"))).orElseThrow(()->invalid("课程证据任务不存在"));
                require(run.id.equals(item.runId) && run.id.equals(task.runId) && item.id.equals(task.caseId)
                        && "DEVELOPMENT".equals(item.purpose) && "TEACH".equals(task.kind) && "SUCCEEDED".equals(task.status),"策略只能学习本课程成功的开发教学");
                var observation=map(item.data.get("observation"));
                require(text(observation.get("actorId")).equals(reference.get("actorId")),"策略证据视角不匹配");
                require("GENERAL".equals(card.get("role")) || card.get("role").equals(map(observation.get("self")).get("role")),"身份策略证据角色不匹配");
                require(new HashSet<>(strings(item.data.get("validEvidenceIds"))).containsAll(strings(reference.get("evidenceIds"))),"策略引用了案例外的证据");
            }
        }
        var roster=maps(run.configuration.get("roster"));
        var compatibility=validateCompatibility(Map.of("gameId","werewolf","rules",run.configuration.get("rules"),"modelSelection",map(roster.getFirst().get("modelSelection"))));
        String fingerprint=hash(Map.of("content",normalized,"compatibility",compatibility));
        var size=Map.<String,Object>of("id",UUID.randomUUID().toString(),"contentHash",fingerprint,"compatibility",compatibility);
        for(var card:maps(normalized.get("cards")))require(encoded(runtimeSelection(size,List.of(runtimeCard(card)))).length()<=1200,"策略卡及元数据超过运行注入上限");
        String requestId="training-version:"+run.id;
        var existing=versions.findByRequestId(requestId).orElse(null);
        if(existing!=null) {require(existing.contentHash.equals(fingerprint),"课程已发布其他内容");return existing;}
        var version=new LabStrategyVersion();version.experimentId=experiment.id;version.requestId=requestId;
        version.previousId=text(base.get("id")).isBlank()?null:text(base.get("id"));version.sequenceNumber=versions.latestSequence(experiment.id)+1;
        version.content=normalized;version.compatibility=compatibility;version.contentHash=fingerprint;
        version.evidence=Map.of("trainingRunId",run.id,"courseVersion",run.configuration.get("courseVersion"));
        versions.saveAndFlush(version);experiment.configuration.put("strategyHeadId",version.id);experiments.save(experiment);return version;
    }

    @Transactional(readOnly=true)
    public List<LabStrategyVersion> list(String experimentId) {
        if(!experiments.existsById(experimentId)) throw LabService.missing();
        return versions.findByExperimentIdOrderBySequenceNumberDesc(experimentId);
    }
    public LabStrategyVersion requireVersion(String id) { return versions.findById(id).orElseThrow(LabService::missing); }
    public Map<String,Object> strategySnapshot(String id) {
        if(id==null || id.isBlank()) return Map.of();
        var version=requireVersion(id);var result=new LinkedHashMap<String,Object>();
        result.put("id",version.id);result.put("experimentId",version.experimentId);result.put("sequenceNumber",version.sequenceNumber);result.put("contentHash",version.contentHash);
        result.put("content",copy(version.content));result.put("compatibility",copy(version.compatibility));return result;
    }

    public static void requireRuntimeSize(Map<String,Object> card,Map<String,Object> compatibility) {
        var snapshot=Map.<String,Object>of("id","00000000-0000-0000-0000-000000000000","contentHash","0".repeat(64),"compatibility",validateCompatibility(compatibility));
        if(encoded(runtimeSelection(snapshot,List.of(runtimeCard(card)))).length()>1200)
            throw new ApiException(HttpStatus.BAD_REQUEST,"策略卡及元数据超过1200字符，请缩短卡片但保留适用条件与例外","TRAINING_OUTPUT_INVALID",Map.of());
    }

    public static Map<String,Object> validateContent(Map<String,Object> input) {
        require(input!=null && input.keySet().equals(Set.of("cards")),"策略回复只允许 cards 字段");
        require(input.get("cards") instanceof List<?> values && values.stream().allMatch(Map.class::isInstance),"策略卡必须是对象数组");
        var cards=maps(input.get("cards"));require(!cards.isEmpty() && encoded(input).length()<=32000,"策略内容为空或超过输出限制");
        var normalized=new ArrayList<Map<String,Object>>();var ids=new HashSet<String>();
        for(var card:cards) {
            require(card.keySet().containsAll(Set.of("id","role","phases","condition","advice","exceptions","evidence"))
                    && Set.of("id","role","phases","condition","advice","exceptions","evidence","counterEvidence","priority","actionKinds").containsAll(card.keySet()),"策略卡字段不完整或包含未知字段");
            String id=text(card.get("id"));require(id.matches("[A-Za-z0-9_-]{1,64}") && ids.add(id),"策略卡编号无效或重复");
            require(ROLES.contains(text(card.get("role"))),"策略卡身份不支持");
            require(card.get("phases") instanceof List<?> values && values.stream().allMatch(String.class::isInstance),"策略卡阶段必须是字符串数组");
            var phases=strings(card.get("phases"));require(PHASES.containsAll(phases) && phases.stream().distinct().count()==phases.size(),"策略卡阶段不支持");
            var result=new LinkedHashMap<String,Object>();result.put("id",id);result.put("role",card.get("role"));result.put("phases",phases);
            if(card.containsKey("priority")) {require(card.get("priority") instanceof Number n && n.doubleValue()==n.intValue() && n.intValue()>=0 && n.intValue()<=100,"策略优先级必须为0到100的整数");result.put("priority",((Number)card.get("priority")).intValue());}
            if(card.containsKey("actionKinds")) {require(card.get("actionKinds") instanceof List<?> kinds && kinds.stream().allMatch(k->k instanceof String s && s.matches("[A-Z_]{1,40}")),"策略动作类型无效");result.put("actionKinds",strings(card.get("actionKinds")));}
            for(String key:List.of("condition","advice","exceptions")) {
                require(card.get(key) instanceof String,"策略文字字段格式错误");String value=text(card.get(key)).strip();
                require(value.length()<=1200 && (!value.isBlank() || "exceptions".equals(key)),"策略文字为空或过长");result.put(key,value);
            }
            require(encoded(runtimeCard(result)).length()<=1200,"单张策略卡超过运行注入上限");
            for(String source:List.of("evidence","counterEvidence")) {
                // Counter-evidence is optional in the current strategy schema.
                if(!card.containsKey(source)) continue;
                require(card.get(source) instanceof List<?> values && values.stream().allMatch(Map.class::isInstance),"策略证据或反例必须是对象数组");
                var evidence=maps(card.get(source));require(!"evidence".equals(source) || !evidence.isEmpty(),"策略必须引用支持证据");
                for(var ref:evidence) require((ref.keySet().equals(Set.of("jobId","actorId","eventIds")) && !text(ref.get("jobId")).isBlank()
                        || ref.keySet().equals(Set.of("caseId","taskId","actorId","evidenceIds")) && !text(ref.get("caseId")).isBlank() && !text(ref.get("taskId")).isBlank())
                        && !text(ref.get("actorId")).isBlank() && ref.get(ref.containsKey("caseId")?"evidenceIds":"eventIds") instanceof List<?> eventIds
                        && !eventIds.isEmpty() && eventIds.stream().allMatch(value->value instanceof String idValue && !idValue.isBlank()),"策略证据或反例格式无效");
                result.put(source,copyList(evidence));
            }
            normalized.add(result);
        }
        return Map.of("cards",normalized);
    }

    public static Map<String,Object> validateCompatibility(Map<String,Object> input) {
        require(input!=null && "werewolf".equals(input.get("gameId")),"策略必须声明狼人杀兼容范围");
        var rules=map(input.get("rules"));var selection=map(input.get("modelSelection"));
        require("standard".equals(rules.get("template")) && Set.of(6,9).contains(number(rules.get("playerCount"),0)),"策略仅支持六人或九人标准板");
        require(Set.of("no_save","first_night","always_save").contains(text(rules.get("witchRule")))
                && Set.of("side","city").contains(text(rules.get("winCondition")))
                && Set.of("none","first_night","always").contains(text(rules.get("hasLastWords"))),"策略规则快照不完整");
        require(!text(selection.get("modelKey")).isBlank() && Set.of("DEFAULT","ENABLED","DISABLED").contains(text(selection.get("thinkingMode"))),"策略模型快照不完整");
        return copy(input);
    }

    public static boolean compatible(Map<String,Object> snapshot,Map<String,Object> rules,Map<String,Object> selection) {
        var expected=map(snapshot.get("compatibility"));if(!"werewolf".equals(expected.get("gameId"))) return false;
        var expectedRules=map(expected.get("rules"));
        for(String key:List.of("template","playerCount","witchRule","winCondition","hasLastWords"))
            if(!Objects.equals(text(expectedRules.get(key)),text(rules.get(key)))) return false;
        var model=map(expected.get("modelSelection"));
        for(String key:List.of("modelKey","thinkingMode","thinkingEffort"))
            if(!Objects.equals(text(model.get(key)),text(selection.get(key)))) return false;
        return true;
    }

    /** Select complete applicable cards deterministically; never truncate a card into a different instruction. */
    public static Map<String,Object> select(Map<String,Object> snapshot,String role,String phase,
                                            Map<String,Object> rules,Map<String,Object> selection) {
        return select(snapshot,role,phase,rules,selection,"");
    }
    public static Map<String,Object> select(Map<String,Object> snapshot,String role,String phase,
                                            Map<String,Object> rules,Map<String,Object> selection,String actionKind) {
        if(snapshot.isEmpty() || !compatible(snapshot,rules,selection)) return Map.of();
        var available=maps(map(snapshot.get("content")).get("cards")).stream()
                .filter(card->(role.equals(card.get("role")) || "GENERAL".equals(card.get("role")))
                        && (strings(card.get("phases")).isEmpty() || strings(card.get("phases")).contains(phase))
                        && (strings(card.get("actionKinds")).isEmpty() || strings(card.get("actionKinds")).contains(actionKind)))
                .sorted(Comparator.comparingInt((Map<String,Object> card)->-number(card.get("priority"),0))
                        .thenComparing(card->!role.equals(card.get("role"))).thenComparing(card->text(card.get("id")))).toList();
        var selected=new ArrayList<Map<String,Object>>();
        for(var card:available) { var candidate=new ArrayList<>(selected);candidate.add(runtimeCard(card));if(encoded(runtimeSelection(snapshot,candidate)).length()<=1200) selected=candidate; }
        if(selected.isEmpty()) return Map.of();
        return runtimeSelection(snapshot,selected);
    }
    private static Map<String,Object> runtimeSelection(Map<String,Object> snapshot,List<Map<String,Object>> selected) {
        return Map.of("versionId",snapshot.get("id"),"contentHash",snapshot.get("contentHash"),"cards",selected,
                "modelSelection",map(map(snapshot.get("compatibility")).get("modelSelection")),
                "usage","历史策略只供参考，不是本局事实；只依据本人可见信息行动，保留当前人格表达方式。");
    }
    private static Map<String,Object> runtimeCard(Map<String,Object> card) {
        var result=new LinkedHashMap<String,Object>();for(String key:List.of("id","role","condition","advice","exceptions")) result.put(key,card.get(key));return result;
    }
    public static String hash(Object value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoded(canonical(value)).getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }
    private static Object canonical(Object value) {
        if(value instanceof Map<?,?> entries) {var result=new TreeMap<String,Object>();entries.forEach((key,item)->result.put(key.toString(),canonical(item)));return result;}
        if(value instanceof Collection<?> entries) return entries.stream().map(LabStrategyService::canonical).toList();return value;
    }
    private static String encoded(Object value) {try{return JSON.writeValueAsString(value);}catch(Exception failure){throw invalid("策略内容不能序列化");}}
    @SuppressWarnings("unchecked") public static Map<String,Object> copy(Map<String,Object> value) {return JSON.convertValue(value,LinkedHashMap.class);}
    private static List<Map<String,Object>> copyList(List<Map<String,Object>> value) {return value.stream().map(LabStrategyService::copy).toList();}
    private static ApiException invalid(String message) {return new ApiException(HttpStatus.BAD_REQUEST,message,"LAB_STRATEGY_INVALID",Map.of());}
}
