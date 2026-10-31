package com.aisocialgame.lab;

import com.aisocialgame.model.GameState;
import com.aisocialgame.repository.AiTurnJobRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import java.util.*;
import static com.aisocialgame.engine.v2.RuleSupport.*;

/** A post-game teacher gets outcome truth separately from each actor's actual decision-time evidence. */
@Service
public class WerewolfLabReview {
    static final String SYSTEM="""
        你是狼人杀实验室复盘教练，目标是可靠推理、合理技能与自然交流，保留四个人格的差异，不修改人格。
        对局已结束。outcomeTruth 是事后真相，不是角色当时知道的事实。decisions 中每项是实际行动前保存的观察；
        visibleEventIds 指向 eventCatalog，只有该决策列出的事件可以作为该 actor 当时可知的证据。
        previousStrategy 是旧共享策略。按通用 GENERAL、狼人 WEREWOLF、村民 VILLAGER、预言家 SEER、女巫 WITCH、猎人 HUNTER 整理短小条件策略。
        赢了不代表每步正确，输了不代表推理差。狼人有意伪装合法，区分伪装与误记；留药、放弃开枪可以合理。
        没收到的上下文标为输入缺口；FAILED/OBSOLETE、超时、网络或无结果不是策略错误，不写成应弃票或应沉默。
        只新增或修改本局成功行动有证据支持的条目；未触发的身份继承旧条目，不编造已学会技能。
        每条包含适用条件、建议、例外，不保留座号/姓名的固定身份，不抄发言模板。不得把别人的私有信息当成本人可知。
        保留旧条目稳定 id，原样保留未改条目及其旧证据；改动条目引用本局 SUCCEEDED 的 jobId、其 actorId 和非空可见 eventIds。
        每次返回完整策略卡集合，可返回与旧版完全相同的集合表示无可靠更新。phases 为空表示适用于所有阶段。
        若本局存在反例，可选填 counterEvidence，结构与 evidence 相同，也只引用对应本人成功任务中的可见事件；在 exceptions 解释其适用边界。没有反例时省略，不编造。
        每条使用简短中文，每条建议约100字，完整运行策略会按身份和阶段限长选择，不额外调用模型。
        id 必须符合 [A-Za-z0-9_-]{1,64}，使用英文短ID。仅返回JSON：{"cards":[{"id":"stable_ascii_id","role":"GENERAL","phases":[],"condition":"适用情境","advice":"建议做法","exceptions":"不适用条件","evidence":[{"jobId":"原任务ID","actorId":"该任务本人ID","eventIds":["该任务当时可见事件ID"]}]}]}。
        不输出Markdown、思考过程或无证据的评分快照。
        """;
    private final AiTurnJobRepository jobs;
    private final com.aisocialgame.repository.AiDecisionTraceRepository traces;
    private final LabStrategyService strategies;
    private final ObjectMapper json;
    public WerewolfLabReview(AiTurnJobRepository jobs,com.aisocialgame.repository.AiDecisionTraceRepository traces,LabStrategyService strategies,ObjectMapper json) {
        this.jobs=jobs; this.traces=traces; this.strategies=strategies; this.json=json;
    }
    public Map<String,Object> request(LabExperiment experiment,LabMatch match,GameState state) {
        var catalog=new LinkedHashMap<String,Object>();
        var decisions=new ArrayList<Map<String,Object>>();
        var byJob=new HashMap<String,com.aisocialgame.model.AiDecisionTrace>();
        for(String actor:map(match.snapshot.get("players")).keySet())
            for(var trace:traces.findByRoomIdAndInstanceIdAndActorPlayerId(match.roomId,match.archiveId,actor))
                byJob.put(text(trace.getQuality().get("jobId")),trace);
        for(var job:jobs.findByInstanceIdOrderByCreatedAtAsc(match.archiveId)) {
            var observation=job.getObservation();
            var row=new LinkedHashMap<String,Object>();
            row.put("jobId",job.getId()); row.put("actorId",job.getActorId()); row.put("status",job.getStatus()); row.put("kind",job.getKind());
            for(String key:List.of("phase","round","privateFacts","legalActions"))
                if(observation.containsKey(key)) row.put(key,observation.get(key));
            row.put("self",displaySeat(map(observation.get("self"))));
            row.put("players",maps(observation.get("players")).stream().map(WerewolfLabReview::displaySeat).toList());
            var events=maps(observation.get("events"));
            for(var event:events) catalog.putIfAbsent(text(event.get("eventId")),event);
            row.put("visibleEventIds",events.stream().map(event -> text(event.get("eventId"))).toList());
            row.put("usedStrategy",map(map(observation.get("memory")).get("strategy")));
            row.put("failureReasons",strings(job.getDiagnostics().get("failures")));
            var trace=byJob.get(job.getId());
            var canonical=map(job.getDiagnostics().get("canonicalOutput"));
            var output=canonical.isEmpty()?(trace==null?Map.<String,Object>of():trace.getRawOutput()):canonical;
            if(output.containsKey("action")) row.put("actualAction",output.get("action"));
            var generatedIds=trace==null?Set.<String>of():new HashSet<>(strings(trace.getQuality().get("eventIds")));
            row.put("generatedEvents",maps(state.getData().get("events")).stream()
                    .filter(event -> generatedIds.contains(text(event.get("eventId"))))
                    .map(event -> Map.of("eventId",event.get("eventId"),"message",text(event.get("message")),"phase",text(event.get("phase"))))
                    .toList());
            decisions.add(row);
        }
        var previous=map(experiment.configuration.get("initialStrategy"));
        if(!text(experiment.configuration.get("strategyHeadId")).isBlank()) previous=strategies.strategySnapshot(text(experiment.configuration.get("strategyHeadId")));
        var input=new LinkedHashMap<String,Object>();
        input.put("rules",map(match.snapshot.get("rules"))); input.put("previousStrategy",previous);
        input.put("seatNumbering","decisions.self 与 players 的 seatNumber 是与玩家实际提示一致的1起始显示座号，发言中的几号按此映射。playerId是不透明标识。eventCatalog是原始事件，其data内部序号不作为显示座号；优先按actorId/targetId对应玩家。");
        input.put("eventCatalog",catalog); input.put("decisions",decisions);
        input.put("outcomeTruth",Map.of("results",match.snapshot.getOrDefault("results",List.of()),"winner",match.snapshot.getOrDefault("winner","")));
        try {
            return Map.of("roomId",match.roomId,"gameId","werewolf",
                    "selection",match.snapshot.get("coach"),"inputVersion","werewolf-review-v1",
                    "messages",List.of(Map.of("role","system","content",SYSTEM),Map.of("role","user","content",json.writeValueAsString(input))));
        } catch(com.fasterxml.jackson.core.JsonProcessingException invalid) { throw new IllegalStateException(invalid); }
    }
    private static Map<String,Object> displaySeat(Map<String,Object> player) {
        var result=new LinkedHashMap<>(player);
        if(result.get("seatNumber") instanceof Number number) result.put("seatNumber",number.intValue()+1);
        return result;
    }
    public void publish(LabExperiment experiment,LabMatch match,String requestId,Map<String,Object> output) {
        if(output.keySet().equals(Set.of("cards")) && output.get("cards") instanceof List<?> cards && cards.isEmpty()) {
            match.snapshot.put("strategyNoChange",true);
            match.snapshot.put("strategyVersionId",text(experiment.configuration.get("strategyHeadId")));
            return;
        }
        String previousId=text(experiment.configuration.get("strategyHeadId"));
        if(!previousId.isBlank() && map(strategies.strategySnapshot(previousId).get("content")).equals(output)) {
            match.snapshot.put("strategyNoChange",true);match.snapshot.put("strategyVersionId",previousId);return;
        }
        var profiles=map(match.snapshot.get("players"));
        var compatibility=Map.<String,Object>of("gameId","werewolf","rules",map(experiment.configuration.get("rules")),
                "modelSelection",map(map(profiles.values().iterator().next()).get("modelSelection")));
        LabStrategyVersion version;
        try {version=strategies.createVersion(experiment,match,requestId,output,compatibility);}
        catch(com.aisocialgame.exception.ApiException invalid) {
            throw new com.aisocialgame.exception.ApiException(org.springframework.http.HttpStatus.BAD_REQUEST,invalid.getMessage(),"LAB_REVIEW_INVALID",Map.of());
        }
        match.snapshot.put("strategyVersionId",version.id);
    }
}
