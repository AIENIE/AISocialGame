package com.aisocialgame.lab;

import com.aisocialgame.config.AppProperties;
import com.aisocialgame.exception.ApiException;
import com.aisocialgame.integration.grpc.client.AiGrpcClient;
import com.aisocialgame.integration.grpc.dto.*;
import com.aisocialgame.service.ai.config.AiModelSelection;
import com.aisocialgame.service.credit.BoundedAiClient;
import com.aisocialgame.service.safety.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import static com.aisocialgame.engine.v2.RuleSupport.*;

/** One explicitly requested rereview. Its durable cursor never advances the automatic game cursor. */
@Service
public class LabManualReviewService {
    private final LabExperimentRepository experiments;
    private final LabMatchRepository matches;
    private final LabCoachService coach;
    private final LabBudgetService budgets;
    private final AiGrpcClient client;
    private final BoundedAiClient calls;
    private final AppProperties properties;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private record Claim(String experimentId,String matchId,String operationId,String requestId,Map<String,Object> request) {}

    public LabManualReviewService(LabExperimentRepository experiments,LabMatchRepository matches,LabCoachService coach,
            LabBudgetService budgets,AiGrpcClient client,BoundedAiClient calls,AppProperties properties,ObjectMapper json,PlatformTransactionManager manager) {
        this.experiments=experiments;this.matches=matches;this.coach=coach;this.budgets=budgets;this.client=client;
        this.calls=calls;this.properties=properties;this.json=json;tx=new TransactionTemplate(manager);
    }

    public Map<String,Object> create(String experimentId,String matchId,LabRequests.Review request) {
        var selection=request.coach().snapshot();
        var original=match(experimentId,matchId);
        var existing=runs(original).get(request.operationId());
        if (existing!=null) { sameParameters(map(existing),selection);return inspect(experimentId,matchId,request.operationId()); }
        client.preflight(properties.getProjectKey(),properties.getAi().getSystemUserId(),selection,LabCoachLimits.MAX_OUTPUT_TOKENS);
        tx.executeWithoutResult(status->{
            var experiment=experiments.lock(experimentId).orElseThrow(LabService::missing);
            var match=matches.lock(matchId).orElseThrow(LabService::missing);
            require(experimentId.equals(match.experimentId),"对局不属于此实验");
            var runs=runs(match);
            if (runs.containsKey(request.operationId())) { sameParameters(map(runs.get(request.operationId())),selection);return; }
            require("PAUSED".equals(experiment.status),"单次复盘要求实验处于暂停状态");
            require(Set.of("FINISHED","REVIEW_FAILED","REVIEWED").contains(match.status) && match.evaluationId==null && match.archiveId!=null,"仅可重新复盘正常结束并已归档的训练对局");
            require(!experiment.configuration.containsKey("manualReview"),"已有单次复盘正在处理");
            require(!experiment.configuration.containsKey("activeScenario"),"请先结束情境评测");
            budgets.requireRemaining(experiment);
            var input=new LinkedHashMap<>(map(match.snapshot.get("reviewRequest")));
            var messages=maps(input.get("messages"));
            boolean werewolf=LabGameConfig.werewolf(experiment.configuration);
            require(messages.size()>=2 && (werewolf
                    ? "werewolf".equals(input.get("gameId")) && "werewolf-review-v1".equals(input.get("inputVersion"))
                    : !strings(match.snapshot.get("reviewEventIds")).isEmpty()),"原始复盘输入快照不可用");
            // Preserve the original decision evidence and historical experience; only the coach selection changes.
            input.put("messages",new ArrayList<>(messages.subList(0,2)));input.put("selection",selection.toMap());
            String requestId="lab-rereview:"+matchId+":"+request.operationId();
            var run=new LinkedHashMap<String,Object>();
            run.put("taskId",UUID.randomUUID().toString());run.put("operationId",request.operationId());
            run.put("status","QUEUED");run.put("createdAtEpochMs",System.currentTimeMillis());
            run.put("coach",selection.toMap());run.put("request",input);run.put("baseRequestId",requestId);run.put("requestId",requestId);
            run.put("requestIds",new ArrayList<>(List.of(requestId)));run.put("previousHeads",headIds(experiment));
            if(werewolf) run.put("previousStrategyVersionId",text(experiment.configuration.get("strategyHeadId")));
            runs.put(request.operationId(),run);match.snapshot.put("manualReviews",runs);
            experiment.configuration.put("manualReview",Map.of("matchId",matchId,"operationId",request.operationId()));
            experiment.configuration.put("coach",selection.toMap());
            matches.saveAndFlush(match);experiments.saveAndFlush(experiment);
        });
        return inspect(experimentId,matchId,request.operationId());
    }

    public Map<String,Object> inspect(String experimentId,String matchId,String operationId) {
        var run=map(runs(match(experimentId,matchId)).get(operationId));
        if (run.isEmpty()) throw LabService.missing();
        var result=new LinkedHashMap<>(run);
        result.put("call",calls.inspect(properties.getProjectKey(),properties.getAi().getSystemUserId(),text(run.get("requestId"))));
        result.put("experimentStatus",experiments.findById(experimentId).orElseThrow().status);
        return result;
    }

    public boolean process(String experimentId) {
        Claim claim=claim(experimentId);if (claim==null) return false;
        try (var lab=LabCallContext.open(Map.of("experimentId",experimentId,"matchId",claim.matchId()),"MANUAL_REVIEW")) {
            var selection=AiModelSelection.fromMap(map(claim.request().get("selection")));
            var messages=maps(claim.request().get("messages")).stream().map(m->new AiChatMessageDto(text(m.get("role")),text(m.get("content")))).toList();
            try(var scope=AiCallScope.open(AiSafetyContext.source("AI_PLAYER").room(text(claim.request().get("roomId")),text(claim.request().getOrDefault("gameId","undercover")))
                    .user(Long.toString(properties.getAi().getSystemUserId()),Long.toString(properties.getAi().getSystemUserId())).model(selection.modelKey()))) {
                var response=client.undercoverChatCompletions(properties.getProjectKey(),properties.getAi().getSystemUserId(),
                        "lab-review-"+claim.matchId(),selection,messages,claim.requestId(),LabCoachLimits.TOTAL_SECONDS);
                LabCallRecovery.commit(()->saveResponse(claim,response));
                var output=LabCoachService.parse(response,json);
                LabCallRecovery.commit(()->publish(claim,output));
            }
        } catch (RuntimeException failure) { LabCallRecovery.commit(()->failed(claim,failure)); }
        return true;
    }

    private Claim claim(String id) {
        return tx.execute(status->{
            var experiment=experiments.lock(id).orElseThrow();
            if (!"PAUSED".equals(experiment.status)) return null;
            var pointer=map(experiment.configuration.get("manualReview"));if (pointer.isEmpty()) return null;
            var match=matches.lock(text(pointer.get("matchId"))).orElseThrow();
            String operationId=text(pointer.get("operationId"));var run=map(runs(match).get(operationId));
            if (!Set.of("QUEUED","RUNNING","WAITING").contains(text(run.get("status")))) return null;
            long now=System.currentTimeMillis();
            if (run.get("leaseUntilEpochMs") instanceof Number lease && lease.longValue()>now) return null;
            var recovery=map(run.get("recovery"));if (!LabCallRecovery.ready(recovery,now)) return null;
            String requestId=LabCallRecovery.requestId(text(run.get("baseRequestId")),recovery);
            run.put("status","RUNNING");run.put("requestId",requestId);run.put("leaseUntilEpochMs",now+LabCoachLimits.LEASE_MS);
            var ids=new ArrayList<>(strings(run.get("requestIds")));if (!ids.contains(requestId)) ids.add(requestId);run.put("requestIds",ids);
            saveRun(match,operationId,run);
            return new Claim(id,match.id,operationId,requestId,map(run.get("request")));
        });
    }

    private void saveResponse(Claim claim,AiChatResult response) {
        tx.executeWithoutResult(status->{
            var match=matches.lock(claim.matchId()).orElseThrow();var run=map(runs(match).get(claim.operationId()));
            run.put("response",Map.of("requestId",claim.requestId(),"content",text(response.content()),"finishReason",text(response.finishReason())));
            saveRun(match,claim.operationId(),run);
        });
    }

    private void publish(Claim claim,Map<String,Object> output) {
        tx.executeWithoutResult(status->{
            var experiment=experiments.lock(claim.experimentId()).orElseThrow();var match=matches.lock(claim.matchId()).orElseThrow();
            var run=map(runs(match).get(claim.operationId()));
            if ("SUCCEEDED".equals(run.get("status"))) return;
            require("PAUSED".equals(experiment.status),"实验状态已变化，原回复已保留");
            require(claim.requestId().equals(run.get("requestId")),"复盘请求身份不一致");
            require(headIds(experiment).equals(map(run.get("previousHeads"))),"当前经验已变化，原回复已保留");
            if(LabGameConfig.werewolf(experiment.configuration)) {match.status="REVIEWING";match.reason=null;}
            coach.publish(experiment,match,claim.requestId(),output);
            match.status="REVIEWED";match.reason=null;match.reviewLeaseUntil=null;
            run.put("publishedHeads",headIds(experiment));run.put("status","SUCCEEDED");run.remove("leaseUntilEpochMs");
            if(LabGameConfig.werewolf(experiment.configuration)) run.put("publishedStrategyVersionId",text(experiment.configuration.get("strategyHeadId")));
            run.put("completedAtEpochMs",System.currentTimeMillis());run.remove("reason");
            saveRun(match,claim.operationId(),run);
            experiment.configuration.remove("manualReview");
            if (budgets.spentPoints(experiment.id)>=experiment.budgetPoints) LabBudgetService.markPaused(experiment);
            experiments.saveAndFlush(experiment);
        });
    }

    private void failed(Claim claim,RuntimeException failure) {
        tx.executeWithoutResult(status->{
            var experiment=experiments.lock(claim.experimentId()).orElseThrow();var match=matches.lock(claim.matchId()).orElseThrow();
            var run=map(runs(match).get(claim.operationId()));if ("SUCCEEDED".equals(run.get("status"))) return;
            String reason=failure instanceof ApiException api && api.getCode()!=null?api.getCode():"LAB_REVIEW_FAILED";
            boolean retry=false;
            if (failure instanceof ApiException api && LabCallRecovery.recoverable(reason)) {
                var recovery=map(run.get("recovery"));reason=LabCallRecovery.defer(recovery,api,System.currentTimeMillis(),LabCoachLimits.LEASE_MS);
                run.put("recovery",recovery);retry=!LabCallRecovery.blocked(reason);
            }
            run.put("status",retry?"WAITING":LabCallRecovery.blocked(reason)?"BLOCKED":"FAILED");
            run.put("reason",reason);run.remove("leaseUntilEpochMs");saveRun(match,claim.operationId(),run);
            if (!retry) {
                experiment.configuration.remove("manualReview");
                if ("PAUSED".equals(experiment.status)) experiment.reason=reason;
                experiments.saveAndFlush(experiment);
            }
        });
    }
    private LabMatch match(String experimentId,String matchId) {
        var match=matches.findById(matchId).orElseThrow(LabService::missing);
        require(experimentId.equals(match.experimentId),"对局不属于此实验");return match;
    }
    private void saveRun(LabMatch match,String operationId,Map<String,Object> run) {
        var runs=runs(match);runs.put(operationId,run);match.snapshot.put("manualReviews",runs);matches.saveAndFlush(match);
    }
    private static Map<String,Object> runs(LabMatch match) { return new LinkedHashMap<>(map(match.snapshot.get("manualReviews"))); }
    private static Map<String,Object> headIds(LabExperiment experiment) {
        if(LabGameConfig.werewolf(experiment.configuration)) return Map.of("strategyHeadId",text(experiment.configuration.get("strategyHeadId")));
        var ids=new LinkedHashMap<String,Object>();map(experiment.configuration.get("experiences")).forEach((id,head)->ids.put(id,text(map(head).get("experienceId"))));return ids;
    }
    private static void sameParameters(Map<String,Object> run,AiModelSelection selection) {
        if (!AiModelSelection.fromMap(map(run.get("coach"))).equals(selection))
            throw new ApiException(HttpStatus.CONFLICT,"operationId 已用于不同的教练配置","LAB_REVIEW_OPERATION_CONFLICT",Map.of());
    }
}
