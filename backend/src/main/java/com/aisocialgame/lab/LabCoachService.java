package com.aisocialgame.lab;

import com.aisocialgame.config.AppProperties;
import com.aisocialgame.exception.ApiException;
import com.aisocialgame.integration.grpc.client.AiGrpcClient;
import com.aisocialgame.integration.grpc.dto.AiChatMessageDto;
import com.aisocialgame.repository.GameStateRepository;
import com.aisocialgame.service.ai.config.AiModelSelection;
import com.aisocialgame.service.safety.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import static com.aisocialgame.engine.v2.RuleSupport.*;

@Service
public class LabCoachService {
    private final LabBudgetService budgets;
    private static final String SYSTEM = """
        你是“谁是卧底”实验室的局后复盘教练。对局已经结束。
        为每个 AI 分别整合上一版经验和本局有证据支持的策略，关注描述信息量、投票、白板、自爆时机和判断修正。
        经验是可修正的策略建议，不是事实或保证；不要因为获胜就认定每个决策正确。
        只保存泛化策略，不记录词语答案、玩家姓名、原句或某个座位的固定身份。每个 summary 为 1 至 1200 字。
        仅返回 JSON：{"experiences":[{"participantId":"输入中的独立副本ID","summary":"整合后的策略经验","evidenceEventIds":["输入中的公开事件ID"]}]}。
        必须且只能为输入 agents 中的每个 AI 返回一条经验，引用至少一个本局公开事件；不要输出 Markdown 或思考过程。
        """;
    private final LabExperimentRepository experiments;
    private final LabMatchRepository matches;
    private final LabExperienceRepository experiences;
    private final GameStateRepository states;
    private final AiGrpcClient client;
    private final AppProperties properties;
    private final ObjectMapper json;
    private final TransactionTemplate transaction;
    private record Claim(String experimentId,String matchId,String requestId,Map<String,Object> request) {}

    public LabCoachService(LabExperimentRepository experiments,LabMatchRepository matches,LabExperienceRepository experiences,
            GameStateRepository states,AiGrpcClient client,AppProperties properties,ObjectMapper json,PlatformTransactionManager manager,LabBudgetService budgets) {
        this.experiments=experiments; this.matches=matches; this.experiences=experiences; this.states=states;
        this.client=client; this.properties=properties; this.json=json; transaction=new TransactionTemplate(manager); this.budgets=budgets;
    }
    public boolean process(String experimentId) {
        Claim claim=claim(experimentId);
        if(claim==null) return false;
        try(var lab=LabCallContext.open(Map.of("experimentId",claim.experimentId(),"matchId",claim.matchId()),"REVIEW")) {
            var selection=AiModelSelection.fromMap(map(claim.request().get("selection")));
            var messages=maps(claim.request().get("messages")).stream().map(message -> new AiChatMessageDto(text(message.get("role")),text(message.get("content")))).toList();
            try(var scope=AiCallScope.open(AiSafetyContext.source("AI_PLAYER").room(text(claim.request().get("roomId")),"undercover")
                    .user(Long.toString(properties.getAi().getSystemUserId()),Long.toString(properties.getAi().getSystemUserId())).model(selection.modelKey()))) {
                var response=client.undercoverChatCompletions(properties.getProjectKey(),properties.getAi().getSystemUserId(),
                        "lab-review-"+claim.matchId(),selection,messages,claim.requestId(),89);
                if("length".equalsIgnoreCase(response.finishReason())) throw invalid();
                String content=response.content()==null?"":response.content().strip();
                if(content.startsWith("```")) content=content.replaceFirst("^```(?:json)?\\s*","").replaceFirst("\\s*```$","").strip();
                if(content.length()>32000 || !content.startsWith("{") || !content.endsWith("}")) throw invalid();
                finish(claim,map(json.readValue(content,Map.class)));
            }
        } catch(Exception failure) {
            String reason=failure instanceof ApiException api && api.getCode()!=null ? api.getCode() : "LAB_REVIEW_FAILED";
            transaction.executeWithoutResult(status -> {
                var experiment=experiments.lock(claim.experimentId()).orElseThrow();
                var match=matches.lock(claim.matchId()).orElseThrow();
                if(!"REVIEWING".equals(match.status) || !claim.requestId().equals(match.snapshot.get("reviewRequestId"))) return;
                boolean budgetPause=Set.of(LabBudgetService.EXHAUSTED,"LAB_EXPERIMENT_PAUSED").contains(reason);
                match.status=budgetPause?"FINISHED":"REVIEW_FAILED"; match.reason=budgetPause?null:reason;
                match.reviewLeaseUntil=null; matches.save(match);
                if(budgetPause) budgets.pauseIfReached(experiment);
                else LabRuntime.pause(experiment,reason);
                experiments.save(experiment);
            });
        }
        return true;
    }
    private Claim claim(String id) {
        return transaction.execute(status -> {
            var experiment=experiments.lock(id).orElseThrow();
            if(!Set.of("RUNNING","PAUSING").contains(experiment.status)) return null;
            var match=matches.findByExperimentIdAndSequenceNumber(id,experiment.reviewCursor).orElse(null);
            if(match==null || match.evaluationId!=null || !Set.of("FINISHED","REVIEWING").contains(match.status)) return null;
            if("REVIEWING".equals(match.status) && match.reviewLeaseUntil!=null && match.reviewLeaseUntil>System.currentTimeMillis()) return null;
            if(!match.snapshot.containsKey("reviewRequest")) {
                var state=states.findById(match.roomId).orElseThrow();
                require("SETTLEMENT".equals(state.getPhase()) && match.archiveId.equals(state.getData().get("archiveId")),"对局尚未完成");
                var publicEvents=maps(state.getData().get("events")).stream().filter(event -> "PUBLIC".equals(event.get("visibility"))).toList();
                var heads=map(experiment.configuration.get("experiences"));
                var players=map(match.snapshot.get("players"));
                var profiles=new ArrayList<Map<String,Object>>();
                for(var entry:players.entrySet()) {
                    var player=map(entry.getValue()); String participantId=LabService.participantId(player);
                    profiles.add(Map.of("agentId",player.get("agentId"),"participantId",participantId,"actorId",entry.getKey(),
                            "previousExperience",text(map(heads.get(participantId)).get("experience"))));
                }
                try {
                    String input=json.writeValueAsString(Map.of("agents",profiles,"events",publicEvents,"results",match.snapshot.get("results")));
                    match.snapshot.put("reviewRequest",Map.of("roomId",match.roomId,"identityField","participantId","selection",match.snapshot.get("coach"),
                            "messages",List.of(Map.of("role","system","content",SYSTEM),Map.of("role","user","content",input))));
                    match.snapshot.put("reviewEventIds",publicEvents.stream().map(event -> text(event.get("eventId"))).toList());
                } catch(com.fasterxml.jackson.core.JsonProcessingException failure) { throw new IllegalStateException(failure); }
                match.snapshot.put("reviewRequestId","lab-review:"+match.id+":"+number(match.snapshot.get("reviewAttempt"),0));
            }
            match.status="REVIEWING"; match.reviewLeaseUntil=System.currentTimeMillis()+150_000; matches.saveAndFlush(match);
            return new Claim(id,match.id,text(match.snapshot.get("reviewRequestId")),map(match.snapshot.get("reviewRequest")));
        });
    }
    private void finish(Claim claim,Map<String,Object> output) {
        transaction.executeWithoutResult(status -> {
            var experiment=experiments.lock(claim.experimentId()).orElseThrow();
            var match=matches.lock(claim.matchId()).orElseThrow();
            if(!"REVIEWING".equals(match.status) || !claim.requestId().equals(match.snapshot.get("reviewRequestId"))) return;
            var profiles=new HashMap<String,Map<String,Object>>();
            map(match.snapshot.get("players")).values().forEach(value -> { var profile=map(value); profiles.put(LabService.participantId(profile),profile); });
            var expected=profiles.keySet();
            String identityField=text(claim.request().getOrDefault("identityField","agentId"));
            var entries=maps(output.get("experiences"));
            var actual=new HashSet<String>();
            var eventIds=new HashSet<>(strings(match.snapshot.get("reviewEventIds")));
            for(var entry:entries) {
                String participantId=text(entry.get(identityField)),summary=text(entry.get("summary")).strip();
                var evidence=strings(entry.get("evidenceEventIds"));
                if(!expected.contains(participantId) || !actual.add(participantId) || summary.isBlank() || summary.length()>1200
                        || evidence.isEmpty() || !eventIds.containsAll(evidence)) throw invalid();
            }
            if(!actual.equals(expected)) throw invalid();
            var heads=map(experiment.configuration.get("experiences"));
            for(var entry:entries) {
                String participantId=text(entry.get(identityField));
                var version=new LabExperience(); version.experimentId=experiment.id; version.agentId=text(profiles.get(participantId).get("agentId"));
                version.participantId=participantId; version.sequenceNumber=experiences.latestSequence(experiment.id,participantId)+1;
                version.summary=text(entry.get("summary")).strip(); version.previousId=text(map(heads.get(participantId)).get("experienceId"));
                version.evidence=Map.of("matchId",match.id,"archiveId",match.archiveId,"eventIds",strings(entry.get("evidenceEventIds")),"requestId",claim.requestId());
                experiences.saveAndFlush(version); heads.put(participantId,LabService.experienceHead(version));
            }
            experiment.configuration.put("experiences",heads);
            match.status="REVIEWED"; match.reason=null; match.reviewLeaseUntil=null;
            matches.save(match); experiments.save(experiment);
        });
    }
    public void retry(String id) {
        transaction.executeWithoutResult(status -> {
            var experiment=experiments.lock(id).orElseThrow(LabService::missing);
            require("PAUSED".equals(experiment.status),"请先暂停实验");
            var match=matches.findByExperimentIdAndSequenceNumber(id,experiment.reviewCursor).orElseThrow(LabService::missing);
            require("REVIEW_FAILED".equals(match.status),"没有等待处理的复盘");
            budgets.requireRemaining(experiment);
            if(!Set.of("BUDGET_RECONCILIATION_REQUIRED","AI_BUDGET_BUSY").contains(match.reason)) {
                match.snapshot.remove("reviewRequest"); match.snapshot.remove("reviewRequestId");
                match.snapshot.put("reviewAttempt",number(match.snapshot.get("reviewAttempt"),0)+1);
            }
            match.status="FINISHED"; match.reason=null; match.reviewLeaseUntil=null; matches.save(match);
            experiment.status="RUNNING"; experiment.reason=null; experiment.configuration.remove("budgetPaused"); experiments.save(experiment);
        });
    }
    private static ApiException invalid() { return new ApiException(HttpStatus.BAD_GATEWAY,"教练经验格式或证据无效","LAB_REVIEW_INVALID",Map.of()); }
}
