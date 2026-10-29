package com.aisocialgame.lab;

import com.aisocialgame.engine.v2.werewolf.WerewolfRuleSet;
import com.aisocialgame.exception.ApiException;
import com.aisocialgame.service.ai.v2.*;
import com.aisocialgame.service.safety.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import static com.aisocialgame.engine.v2.RuleSupport.*;

/** Frozen, independent situations. Results never enter the learning or publication path. */
@Service
public class LabScenarioService {
    @org.springframework.beans.factory.annotation.Autowired private com.aisocialgame.repository.credit.AiCreditReservationRepository reservations;
    public record Request(String operationId,String candidateStrategyVersionId) {}
    private final LabExperimentRepository experiments;
    private final LabMatchRepository matches;
    private final LabStrategyService strategies;
    private final LabBudgetService budgets;
    private final AiTurnGenerator generator;
    private final WerewolfRuleSet rules;
    private final ObjectMapper json;
    private final TransactionTemplate transaction;
    public LabScenarioService(LabExperimentRepository experiments,LabMatchRepository matches,LabStrategyService strategies,
            LabBudgetService budgets,AiTurnGenerator generator,WerewolfRuleSet rules,ObjectMapper json,PlatformTransactionManager manager) {
        this.experiments=experiments;this.matches=matches;this.strategies=strategies;this.budgets=budgets;
        this.generator=generator;this.rules=rules;this.json=json;this.transaction=new TransactionTemplate(manager);
    }
    public Map<String,Object> create(String id,Request request) {
        require(request.operationId()!=null && request.operationId().matches("[a-zA-Z0-9_-]{8,64}"),"操作 ID 格式无效");
        require(request.candidateStrategyVersionId()!=null,"请选择候选策略");
        transaction.executeWithoutResult(status -> {
            var experiment=experiments.lock(id).orElseThrow(LabService::missing);
            var runs=map(experiment.configuration.get("scenarioRuns"));
            var existing=map(runs.get(request.operationId()));
            if(!existing.isEmpty()) {
                require(request.candidateStrategyVersionId().equals(existing.get("candidateStrategyVersionId")),"同一操作 ID 的参数不能变化");
                return;
            }
            require(LabGameConfig.werewolf(experiment.configuration),"情境评测仅支持狼人杀");
            require(Set.of("PAUSED","COMPLETED").contains(experiment.status),"请先暂停训练");
            require(!experiment.configuration.containsKey("activeScenario") && !experiment.configuration.containsKey("manualReview"),"已有未完成的独立任务");
            require(matches.findByExperimentIdAndStatusInOrderBySequenceNumberAsc(id,List.of("WAITING","PLAYING","FINISHED","REVIEWING","REVIEW_FAILED")).isEmpty(),"请先完成当前局及复盘");
            budgets.requireRemaining(experiment);
            var candidate=strategies.strategySnapshot(request.candidateStrategyVersionId());
            require(id.equals(candidate.get("experimentId")),"候选策略不属于此实验");
            var manifest=maps(experiment.configuration.get("scenarioManifest"));
            require(!manifest.isEmpty(),"此实验没有预先冻结的九人保留题");
            var schedule=new ArrayList<Map<String,Object>>();
            for(var item:manifest) {
                boolean candidateFirst=new Random(((Number)item.get("seed")).longValue()).nextBoolean();
                for(String variant:candidateFirst?List.of("CANDIDATE","BASELINE"):List.of("BASELINE","CANDIDATE"))
                    schedule.add(Map.of("caseId",item.get("caseId"),"variant",variant));
            }
            var run=new LinkedHashMap<String,Object>();
            run.put("id",UUID.randomUUID().toString());run.put("operationId",request.operationId());run.put("candidateStrategyVersionId",request.candidateStrategyVersionId());
            run.put("candidateSnapshot",candidate);run.put("baselineSnapshot",map(experiment.configuration.get("initialStrategy")));
            run.put("returnStatus",experiment.status);run.put("status","RUNNING");run.put("nextIndex",0);run.put("totalCalls",schedule.size());
            run.put("schedule",schedule);run.put("results",new ArrayList<>());run.put("createdAtEpochMs",System.currentTimeMillis());
            run.put("semanticReview","NOT_REVIEWED");run.put("sourceType","RULE_CONSTRUCTED_HOLDOUT");
            runs.put(request.operationId(),run);experiment.configuration.put("scenarioRuns",runs);experiment.configuration.put("activeScenario",request.operationId());
            experiment.status="PAUSED";experiment.reason=null;experiments.saveAndFlush(experiment);
        });
        return inspect(id,request.operationId());
    }
    public Map<String,Object> inspect(String id,String operationId) {
        var experiment=experiments.findById(id).orElseThrow(LabService::missing);
        var run=map(map(experiment.configuration.get("scenarioRuns")).get(operationId));
        require(!run.isEmpty(),"情境评测不存在");
        var result=new LinkedHashMap<>(run);result.put("costs",budgets.totals(id));
        if(reservations!=null) result.put("calls",reservations.findByLabMatchIdOrderByUpdatedAtAsc(text(run.get("id"))).stream().map(call->{
            var row=new LinkedHashMap<String,Object>();row.put("requestId",call.requestId);row.put("budgetId",call.budgetId);row.put("state",call.state);
            row.put("billedPoints",call.billedPoints);row.put("billedCost",call.billedCost);return row;
        }).toList());
        return result;
    }
    private record Claim(String experimentId,String operationId,String runId,int index,String jobId,VisibleObservation observation,Map<String,Object> current) {}
    public boolean process(String id) {
        Claim claim=transaction.execute(status -> claim(id));
        if(claim==null) return false;
        try(var lab=LabCallContext.open(Map.of("experimentId",id,"matchId",claim.runId()),"SCENARIO");
            var scope=AiCallScope.open(AiSafetyContext.source("AI_PLAYER").room(claim.runId(),"werewolf")
                    .persona(text(claim.observation().persona().get("id"))).trace(claim.jobId()))) {
            long remaining=Math.max(0,((Number)claim.current().get("deadlineEpochMs")).longValue()-System.currentTimeMillis());
            var decision=generator.generate(rules,claim.observation(),claim.jobId(),remaining,()->true,map(claim.current().get("checkpoint")),
                    saved -> update(claim,run -> {var current=map(run.get("current"));current.put("checkpoint",saved);run.put("current",current);}));
            var result=new LinkedHashMap<String,Object>();result.put("status","SUCCEEDED");result.put("action",json.convertValue(decision.action(),Map.class));
            result.put("speech",decision.speech());result.put("diagnostics",decision.diagnostics());result.put("evidenceEventIds",decision.evidenceEventIds());
            if(System.currentTimeMillis()>((Number)claim.current().get("deadlineEpochMs")).longValue()) {
                result.put("status","FAILED");result.put("reason","TIMEOUT");result.put("originalResultRecovered",true);
            }
            result.put("semanticReview","NOT_REVIEWED");finish(claim,result);
        } catch(AiTurnGenerator.GenerationDeferred deferred) {
            update(claim,run -> {
                var current=map(run.get("current"));current.put("checkpoint",deferred.checkpoint());
                current.put("remainingMillis",Math.max(0,((Number)current.get("deadlineEpochMs")).longValue()-System.currentTimeMillis()));
                run.put("current",current);run.put("reason",deferred.getMessage());
                if(LabCallRecovery.blocked(deferred.getMessage()) || Set.of("LAB_BUDGET_EXHAUSTED","LAB_EXPERIMENT_PAUSED").contains(deferred.getMessage())) run.put("status","PAUSED");
            });
        } catch(AiTurnGenerator.GenerationFailure failure) {
            var checkpoint=currentCheckpoint(claim);
            if(checkpoint.containsKey("originalRequestId") && !Boolean.TRUE.equals(failure.diagnostics().get("usageComplete"))
                    && number(checkpoint.get("calls"),0)>number(checkpoint.get("responses"),0)) {
                update(claim,run -> {saveRemaining(run);run.put("status","PAUSED");run.put("reason","LAB_CALL_UNCERTAIN");run.put("lastFailure",failure.diagnostics());});
            } else finish(claim,Map.of("status","FAILED","reason",failure.getMessage(),"diagnostics",failure.diagnostics(),"semanticReview","NOT_REVIEWED"));
        } catch(RuntimeException failure) {
            update(claim,run -> {saveRemaining(run);run.put("status","PAUSED");run.put("reason",failure instanceof ApiException api?Objects.toString(api.getCode(),"SCENARIO_LOCAL_ERROR"):"SCENARIO_LOCAL_ERROR");});
        }
        return true;
    }
    private Claim claim(String id) {
        var experiment=experiments.lock(id).orElseThrow(LabService::missing);
        String operation=text(experiment.configuration.get("activeScenario"));
        var run=map(map(experiment.configuration.get("scenarioRuns")).get(operation));
        if(!"PAUSED".equals(experiment.status) || !"RUNNING".equals(run.get("status"))) return null;
        int index=number(run.get("nextIndex"),0);var schedule=maps(run.get("schedule"));
        if(index>=schedule.size()) return null;
        var current=map(run.get("current"));
        if(current.isEmpty()) {
            try {budgets.requireRemaining(experiment);} catch(ApiException exhausted) {
                run.put("status","PAUSED");run.put("reason",exhausted.getCode());LabBudgetService.markPaused(experiment);save(experiment,operation,run);return null;
            }
            var slot=schedule.get(index);
            var item=maps(experiment.configuration.get("scenarioManifest")).stream().filter(row -> row.get("caseId").equals(slot.get("caseId"))).findFirst().orElseThrow();
            var observation=json.convertValue(item.get("observation"),VisibleObservation.class);
            var memory=new LinkedHashMap<>(observation.memory());
            var lab=new LinkedHashMap<>(map(memory.get("lab")));lab.put("experimentId",id);lab.put("matchId",run.get("id"));lab.put("labRunMode","EVALUATION");lab.put("scenarioEvaluation",true);memory.put("lab",lab);
            memory.remove("approved");memory.remove("approvedExperience");memory.remove("strategy");
            var snapshot=map(run.get("CANDIDATE".equals(slot.get("variant"))?"candidateSnapshot":"baselineSnapshot"));
            var selected=LabStrategyService.select(snapshot,text(item.get("role")),observation.phase(),observation.rules(),map(item.get("modelSelection")),observation.turnKind());
            if(!selected.isEmpty()) memory.put("strategy",selected);
            observation=new VisibleObservation(observation.gameId(),observation.instanceId(),observation.phase(),observation.round(),observation.actorId(),observation.turnKind(),
                    observation.self(),observation.players(),observation.rules(),observation.events(),observation.privateFacts(),observation.legalActions(),observation.persona(),memory,observation.knowledge());
            current=new LinkedHashMap<>(slot);current.put("personaId",item.get("personaId"));current.put("role",item.get("role"));
            current.put("observation",json.convertValue(observation,Map.class));current.put("checkpoint",Map.of("modelSelection",item.get("modelSelection")));
            current.put("deadlineEpochMs",System.currentTimeMillis()+50_000);current.put("remainingMillis",50_000);run.put("current",current);save(experiment,operation,run);
        }
        if(!LabCallRecovery.ready(map(current.get("checkpoint")),System.currentTimeMillis())) return null;
        String jobId=UUID.nameUUIDFromBytes((run.get("id")+":"+index).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        var checkpoint=map(current.get("checkpoint"));
        // Old pre-RPC checkpoints may predate the explicit marker; a recorded call must be queried, never resent on restart.
        if(!checkpoint.containsKey("originalRequestId") && number(checkpoint.get("calls"),0)>number(checkpoint.get("responses"),0)) {
            checkpoint.put("originalRequestId",LabCallRecovery.requestId(jobId+":"+number(checkpoint.get("nextAttempt"),0),checkpoint));
            current.put("checkpoint",checkpoint);run.put("current",current);save(experiment,operation,run);
        }
        return new Claim(id,operation,text(run.get("id")),index,jobId,json.convertValue(current.get("observation"),VisibleObservation.class),current);
    }
    private Map<String,Object> currentCheckpoint(Claim claim) {
        var experiment=experiments.findById(claim.experimentId()).orElseThrow(LabService::missing);
        var run=map(map(experiment.configuration.get("scenarioRuns")).get(claim.operationId()));
        return map(map(run.get("current")).get("checkpoint"));
    }
    private void saveRemaining(Map<String,Object> run) {
        var current=map(run.get("current"));
        if(current.get("deadlineEpochMs") instanceof Number deadline) {
            current.put("remainingMillis",Math.max(0,deadline.longValue()-System.currentTimeMillis()));run.put("current",current);
        }
    }
    private void finish(Claim claim,Map<String,Object> outcome) {
        update(claim,run -> {
            var current=map(run.get("current"));var result=new LinkedHashMap<String,Object>();
            for(String key:List.of("caseId","variant","personaId","role","checkpoint")) if(current.containsKey(key)) result.put(key,current.get(key));
            result.putAll(outcome);result.put("jobId",claim.jobId());result.put("completedAtEpochMs",System.currentTimeMillis());
            var results=maps(run.get("results"));results.add(result);run.put("results",results);run.put("nextIndex",claim.index()+1);run.remove("current");run.remove("reason");
            if(claim.index()+1>=number(run.get("totalCalls"),0) && !"STOPPED".equals(run.get("status"))) {run.put("status","COMPLETED");run.put("completedAtEpochMs",System.currentTimeMillis());}
        });
    }
    private void update(Claim claim,java.util.function.Consumer<Map<String,Object>> change) {
        LabCallRecovery.commit(()->transaction.executeWithoutResult(status -> {
            var experiment=experiments.lock(claim.experimentId()).orElseThrow(LabService::missing);
            var run=map(map(experiment.configuration.get("scenarioRuns")).get(claim.operationId()));
            if(number(run.get("nextIndex"),0)!=claim.index() || "COMPLETED".equals(run.get("status"))) return;
            boolean stopped="STOPPED".equals(run.get("status"));
            boolean active=claim.operationId().equals(experiment.configuration.get("activeScenario"));
            String pausedReason="PAUSED".equals(run.get("status"))?text(run.get("reason")):"";
            change.accept(run);
            if(stopped) {run.put("status","STOPPED");run.put("reason","ADMIN_STOPPED");}
            else if(Set.of("ADMIN_PAUSED","LAB_BUDGET_EXHAUSTED").contains(pausedReason) && !"COMPLETED".equals(run.get("status"))) {
                run.put("status","PAUSED");run.put("reason",pausedReason);
            }
            if(active && !stopped && "COMPLETED".equals(run.get("status"))) {
                experiment.configuration.remove("activeScenario");experiment.status=text(run.get("returnStatus"));experiment.reason=null;
            } else if(active && !stopped && "PAUSED".equals(run.get("status"))) {
                experiment.reason=text(run.get("reason"));
                if(LabBudgetService.EXHAUSTED.equals(experiment.reason)) LabBudgetService.markPaused(experiment);
            }
            save(experiment,claim.operationId(),run);
        }));
    }
    private void save(LabExperiment experiment,String operation,Map<String,Object> run) {
        var runs=map(experiment.configuration.get("scenarioRuns"));runs.put(operation,run);experiment.configuration.put("scenarioRuns",runs);experiments.saveAndFlush(experiment);
    }
}
