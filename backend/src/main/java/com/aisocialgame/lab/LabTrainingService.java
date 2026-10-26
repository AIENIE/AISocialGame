package com.aisocialgame.lab;

import com.aisocialgame.config.AppProperties;
import com.aisocialgame.exception.ApiException;
import com.aisocialgame.service.credit.BoundedAiClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import static com.aisocialgame.engine.v2.RuleSupport.*;

/** Persistent course management. Model work is performed by the serial worker outside transactions. */
@Service
public class LabTrainingService {
    public record ExternalDiagnostic(String requestId,String budgetId) {}
    public record Request(String operationId,String goal,List<ExternalDiagnostic> externalDiagnostics) {}
    final LabTrainingRunRepository runs;
    final LabTrainingCaseRepository cases;
    final LabTrainingTaskRepository tasks;
    final LabExperimentRepository experiments;
    final LabMatchRepository matches;
    final LabEvaluationRepository evaluations;
    final LabStrategyService strategies;
    final LabBudgetService budgets;
    final BoundedAiClient calls;
    final AppProperties properties;
    final ObjectMapper json;
    final TransactionTemplate tx;
    final LabTrainingCaseFactory factory;
    private final com.aisocialgame.service.v2.V2GameService game;
    public LabTrainingService(LabTrainingRunRepository runs,LabTrainingCaseRepository cases,LabTrainingTaskRepository tasks,
            LabExperimentRepository experiments,LabMatchRepository matches,LabEvaluationRepository evaluations,
            LabStrategyService strategies,LabBudgetService budgets,BoundedAiClient calls,AppProperties properties,
            ObjectMapper json,PlatformTransactionManager manager,LabTrainingCaseFactory factory,com.aisocialgame.service.v2.V2GameService game) {
        this.runs=runs;this.cases=cases;this.tasks=tasks;this.experiments=experiments;this.matches=matches;this.evaluations=evaluations;
        this.strategies=strategies;this.budgets=budgets;this.calls=calls;this.properties=properties;this.json=json;this.factory=factory;this.game=game;
        this.tx=new TransactionTemplate(manager);
    }
    public Map<String,Object> create(String experimentId,Request request) {
        require(request.operationId()!=null && request.operationId().matches("[A-Za-z0-9_-]{8,64}"),"操作 ID 无效");
        require("RULES_EVIDENCE".equals(request.goal()),"首期课程只支持规则与证据理解");
        var parameters=new LinkedHashMap<String,Object>();parameters.put("goal",request.goal());
        var external=request.externalDiagnostics()==null?List.<ExternalDiagnostic>of():request.externalDiagnostics();
        require(external.size()<=8 && external.stream().map(ExternalDiagnostic::budgetId).distinct().count()==external.size(),"外部诊断重复或过多");
        parameters.put("externalDiagnostics",json.convertValue(external,List.class));String hash=LabStrategyService.hash(parameters);
        var existing=runs.findByExperimentIdAndOperationId(experimentId,request.operationId()).orElse(null);
        if(existing!=null) {sameRequest(existing,hash);return inspect(experimentId,request.operationId());}
        var experiment=experiments.findById(experimentId).orElseThrow(LabService::missing);
        require(LabGameConfig.werewolf(experiment.configuration) && number(experiment.configuration.get("playerCount"),0)==9,"课程只支持九人标准狼人杀");
        require(maps(experiment.configuration.get("roster")).stream().map(p->text(p.get("personaId"))).collect(java.util.stream.Collectors.toSet()).equals(Set.of("ai1","ai2","ai3","ai4")),"课程需要福尔摩斯、小丑、华生和露娜四个人格");
        require(Set.of("PAUSED","DRAFT","COMPLETED").contains(experiment.status),"请先暂停实验");
        require(matches.findByExperimentIdAndStatusInOrderBySequenceNumberAsc(experimentId,List.of("WAITING","PLAYING","REVIEWING")).isEmpty(),"当前还有未结束的对局或复盘");
        var verified=new ArrayList<Map<String,Object>>();
        for(var ref:external) verified.add(calls.inspectExternalCharge(properties.getProjectKey(),properties.getAi().getSystemUserId(),ref.requestId(),ref.budgetId()));
        var draft=new LabTrainingRun();draft.experimentId=experimentId;draft.operationId=request.operationId();draft.requestHash=hash;
        var material=factory.build(experiment,draft.id);
        require(material.stream().filter(row->"DEVELOPMENT".equals(row.get("purpose"))).count()==8
                && material.stream().filter(row->"HOLDOUT".equals(row.get("purpose"))).count()==32,"课程材料须包含八个开发案例和三十二个保留题单元");
        var developmentSources=new HashSet<String>();material.stream().filter(row->"DEVELOPMENT".equals(row.get("purpose"))).forEach(row->developmentSources.add(text(row.get("sourceGroup"))));
        require(material.stream().filter(row->"HOLDOUT".equals(row.get("purpose"))).noneMatch(row->developmentSources.contains(text(row.get("sourceGroup")))),"训练与保留来源重叠");
        String id=tx.execute(status->{
            var locked=experiments.lock(experimentId).orElseThrow(LabService::missing);
            var duplicate=runs.findByExperimentIdAndOperationId(experimentId,request.operationId()).orElse(null);
            if(duplicate!=null) {sameRequest(duplicate,hash);return duplicate.id;}
            require(runs.findByExperimentIdOrderByCreatedAtDesc(experimentId).stream().noneMatch(run->Set.of("DRAFT","RUNNING","PAUSED").contains(run.status)),"已有未完成的课程运行");
            require(Set.of("PAUSED","DRAFT","COMPLETED").contains(locked.status),"实验状态已变化");
            var costs=new LinkedHashMap<>(map(locked.configuration.get("externalDiagnosticCosts")));
            for(var cost:verified) {
                String key=text(cost.get("budgetId"));var old=map(costs.get(key));
                require(old.isEmpty() || old.equals(cost),"外部费用与原记录不一致");costs.put(key,cost);
            }
            locked.configuration.put("externalDiagnosticCosts",costs);
            draft.configuration.put("coach",LabStrategyService.copy(map(locked.configuration.get("coach"))));
            draft.configuration.put("rules",LabStrategyService.copy(map(locked.configuration.get("rules"))));
            draft.configuration.put("roster",LabStrategyService.copy(Map.of("items",maps(locked.configuration.get("roster")))).get("items"));
            String base=text(locked.configuration.get("strategyHeadId"));
            draft.configuration.put("baseStrategy",base.isBlank()?map(locked.configuration.get("initialStrategy")):strategies.strategySnapshot(base));
            draft.configuration.put("randomSeed",locked.configuration.getOrDefault("randomSeed",0L));
            draft.configuration.put("paidRetriesUsed",0);draft.configuration.put("maxPaidRetries",2);
            draft.configuration.put("courseVersion","werewolf-rules-evidence-v2");
            draft.configuration.put("sourceManifestHash",LabStrategyService.hash(material));
            draft.configuration.put("externalDiagnostics",verified);
            runs.saveAndFlush(draft);int ordinal=0;
            for(var row:material) {
                var item=new LabTrainingCase();item.id=text(row.get("id"));item.runId=draft.id;item.ordinal=ordinal++;
                item.purpose=text(row.get("purpose"));item.sourceType=text(row.get("sourceType"));item.sourceGroup=text(row.get("sourceGroup"));
                item.personaId=text(row.get("personaId"));item.participantId=text(row.get("participantId"));item.data=map(row.get("data"));
                item.contentHash=text(row.get("contentHash"));cases.save(item);
                if("DEVELOPMENT".equals(item.purpose)) {
                    addTask(draft,item,"PRACTICE","BASELINE",ordinal*2);
                    addTask(draft,item,"TEACH",null,ordinal*2+1);
                }
            }
            locked.configuration.put("trainingV2Enabled",true);locked.configuration.put("activeTrainingRunId",draft.id);
            locked.status="PAUSED";experiments.saveAndFlush(locked);return draft.id;
        });
        return inspect(experimentId,runs.findById(id).orElseThrow().operationId);
    }
    private void sameRequest(LabTrainingRun run,String hash) {
        if(!run.requestHash.equals(hash)) throw new ApiException(HttpStatus.CONFLICT,"同一操作 ID 的课程参数不同","TRAINING_OPERATION_CONFLICT",Map.of());
    }
    LabTrainingTask addTask(LabTrainingRun run,LabTrainingCase item,String kind,String variant,int ordinal) {
        String key=item.id+":"+kind+":"+text(variant);
        var existing=tasks.findByRunIdAndTaskKey(run.id,key).orElse(null);if(existing!=null)return existing;
        var task=new LabTrainingTask();task.runId=run.id;task.caseId=item.id;task.kind=kind;task.variant=variant;task.ordinal=ordinal;task.taskKey=key;
        return tasks.save(task);
    }
    public Map<String,Object> control(String experimentId,String operation,String action) {
        require(Set.of("start","start-one","pause","resume","stop").contains(action),"课程控制操作无效");
        var original=owned(experimentId,operation);
        if(Set.of("start","start-one","resume").contains(action)) {
            var selections=new ArrayList<Map<String,Object>>();selections.add(map(original.configuration.get("coach")));
            selections.addAll(maps(original.configuration.get("roster")).stream().map(p->map(p.get("modelSelection"))).distinct().toList());
            for(int index=0;index<selections.size();index++) {
                var selection=com.aisocialgame.service.ai.config.AiModelSelection.fromMap(selections.get(index));
                var request=fireflychat.ai.v1.ChatCompletionsRequest.newBuilder().setProjectKey(properties.getProjectKey()).setUserId(properties.getAi().getSystemUserId())
                        .setRequestId("training-preflight:"+UUID.randomUUID()).addMessages(fireflychat.ai.v1.ChatMessage.newBuilder().setRole("user").setContent("Check configured model capability only."));
                selection.apply(request);calls.preflight(request.build(),index==0?LabCoachLimits.MAX_OUTPUT_TOKENS:8192);
            }
        }
        mutate(original.id,(run,experiment)->{
            if(Set.of("COMPLETED","STOPPED").contains(run.status)) {
                require("stop".equals(action) && "STOPPED".equals(run.status),"已结束的课程不能重新开启");return;
            }
            if("pause".equals(action)) {run.status="PAUSED";run.reason="ADMIN_PAUSED";}
            else if("stop".equals(action)) {run.status="STOPPED";run.reason="ADMIN_STOPPED";}
            else {
                boolean retryRepresentative="resume".equals(action) && flag(run.configuration.get("representativeOnly"))
                        && !"REPRESENTATIVE_CASE_READY".equals(run.reason);
                budgets.requireRemaining(experiment);run.status="RUNNING";run.reason=null;
                experiment.configuration.remove("budgetPaused");
                run.configuration.put("representativeOnly","start-one".equals(action) || retryRepresentative);
                for(var task:tasks.findByRunIdOrderByOrdinalAsc(run.id)) {
                    if("BLOCKED".equals(task.status) && !text(task.requestId).isBlank()) {
                        task.status="WAITING";task.notBeforeEpochMs=0;task.checkpoint.remove("waitingSinceEpochMs");tasks.save(task);
                    } else if("FAILED".equals(task.status) && "MATERIAL_TOO_LARGE".equals(task.reason)
                            && Set.of("TEACH","JUDGE").contains(task.kind) && requestIds(task).isEmpty()) {
                        // Explicit resume may re-encode unsent material after a local preparation fix.
                        // Retain the previous input and never reset a task that has a request identity.
                        var history=new ArrayList<>(maps(task.checkpoint.get("inputPreparationHistory")));
                        history.add(Map.of("reason",task.reason,"input",LabStrategyService.copy(task.input),"atEpochMs",System.currentTimeMillis()));
                        task.checkpoint.put("inputPreparationHistory",history);task.input=new LinkedHashMap<>();
                        task.status="PENDING";task.reason=null;tasks.save(task);
                    }
                }
            }
            experiment.status="GAMES".equals(run.stage) && "RUNNING".equals(run.status)?"RUNNING":"PAUSED";
            experiment.reason=run.reason;
            for(var match:matches.findByExperimentIdAndStatusInOrderBySequenceNumberAsc(experiment.id,List.of("WAITING","PLAYING"))) {
                if(!run.id.equals(match.snapshot.get("trainingRunId"))) continue;
                if("STOPPED".equals(run.status)){match.snapshot.put("trainingStopped",true);matches.save(match);}
                if("WAITING".equals(match.status))continue;
                if("RUNNING".equals(run.status)) game.resumeLabBudget(match.roomId);
                else if("STOPPED".equals(run.status)) game.stopLabBudget(match.roomId);
                else game.pauseLabBudget(match.roomId);
            }
            if("STOPPED".equals(run.status)) for(var evaluation:evaluations.findByExperimentIdOrderByCreatedAtDesc(experiment.id))
                if(run.id.equals(evaluation.configuration.get("trainingRunId")) && "RUNNING".equals(evaluation.status)) {evaluation.status="STOPPED";evaluations.save(evaluation);}
        });
        return inspect(experimentId,operation);
    }
    LabTrainingRun owned(String experiment,String operation) {return runs.findByExperimentIdAndOperationId(experiment,operation).orElseThrow(LabService::missing);}
    void mutate(String id,java.util.function.BiConsumer<LabTrainingRun,LabExperiment> operation) {
        LabCallRecovery.commit(()->tx.executeWithoutResult(status->{
            var row=runs.findById(id).orElseThrow(LabService::missing);var experiment=experiments.lock(row.experimentId).orElseThrow(LabService::missing);
            var run=runs.lock(id).orElseThrow();operation.accept(run,experiment);run.updatedAtEpochMs=System.currentTimeMillis();
            runs.saveAndFlush(run);experiments.saveAndFlush(experiment);
        }));
    }
    public Map<String,Object> inspect(String experimentId,String operation) {
        var run=owned(experimentId,operation);var rows=tasks.findByRunIdOrderByOrdinalAsc(run.id);
        var out=new LinkedHashMap<>(summary(run));out.put("configuration",run.configuration);out.put("assessment",run.assessment);
        var progress=new LinkedHashMap<String,Object>();progress.put("totalTasks",rows.size());
        progress.put("completedTasks",rows.stream().filter(t->"SUCCEEDED".equals(t.status)).count());
        progress.put("failedTasks",rows.stream().filter(t->Set.of("FAILED","BLOCKED","SKIPPED").contains(t.status)).count());
        progress.put("developmentCases",8);progress.put("holdoutPairs",32);progress.put("gamePairs",2);
        progress.put("completedHoldoutPairs",rows.stream().filter(t->"JUDGE".equals(t.kind)&&"SUCCEEDED".equals(t.status)).count());
        progress.put("completedGamePairs",run.assessment.getOrDefault("completedGamePairs",0));out.put("progress",progress);
        out.put("cost",cost(experimentId));out.put("tasks",rows.stream().map(this::taskView).toList());
        out.put("nextStep",nextStep(run));return out;
    }
    public Map<String,Object> material(String experiment,String operation) {
        var run=owned(experiment,operation);var rows=cases.findByRunIdOrderByOrdinalAsc(run.id);
        var replaced=map(run.configuration.get("caseReplacements"));
        return Map.of("items",rows,"caseReplacements",replaced,"coverage",Map.of("development",activeDevelopment(run,rows).size(),
                "holdout",rows.stream().filter(c->"HOLDOUT".equals(c.purpose)).count(),"holdoutSourceGroups",rows.stream().filter(c->"HOLDOUT".equals(c.purpose)).map(c->c.sourceGroup).distinct().count(),
                "excluded",rows.stream().filter(c->"EXCLUDED".equals(c.purpose)).count()+replaced.size()));
    }
    static List<LabTrainingCase> activeDevelopment(LabTrainingRun run,List<LabTrainingCase> rows) {
        var replaced=map(run.configuration.get("caseReplacements"));
        return rows.stream().filter(c->"DEVELOPMENT".equals(c.purpose)&&!replaced.containsKey(c.id)).toList();
    }
    static String representativeCaseId(LabTrainingRun run,List<LabTrainingCase> rows) {
        String id=rows.stream().filter(c->"DEVELOPMENT".equals(c.purpose)).map(c->c.id).findFirst().orElse("");
        var replaced=map(run.configuration.get("caseReplacements"));var visited=new HashSet<String>();
        while(replaced.containsKey(id)&&visited.add(id))id=text(replaced.get(id));
        return id;
    }
    public Map<String,Object> cost(String experimentId) {
        var experiment=experiments.findById(experimentId).orElseThrow();var out=new LinkedHashMap<>(budgets.totals(experimentId));
        out.put("externalPoints",LabBudgetService.externalPoints(experiment));out.put("totalKnownPoints",budgets.totalKnownPoints(experiment));
        out.put("budgetPoints",experiment.budgetPoints);out.put("totalCalls",((Number)out.get("totalCalls")).longValue()+map(experiment.configuration.get("externalDiagnosticCosts")).size());return out;
    }
    public List<Map<String,Object>> summaries(String experimentId) {return runs.findByExperimentIdOrderByCreatedAtDesc(experimentId).stream().map(this::summary).toList();}
    Map<String,Object> summary(LabTrainingRun run) {
        var out=new LinkedHashMap<String,Object>();out.put("id",run.id);out.put("experimentId",run.experimentId);out.put("operationId",run.operationId);
        out.put("goal",run.goal);out.put("status",run.status);out.put("stage",run.stage);out.put("reason",run.reason);out.put("candidateStrategyVersionId",run.candidateStrategyVersionId);return out;
    }
    Map<String,Object> taskView(LabTrainingTask row) {
        var out=new LinkedHashMap<String,Object>();out.put("id",row.id);out.put("caseId",row.caseId);out.put("kind",row.kind);out.put("variant",row.variant);
        out.put("status",row.status);out.put("reason",row.reason);out.put("requestId",row.requestId);out.put("attempt",row.attempt);out.put("paidRetries",row.paidRetries);
        out.put("startedAtEpochMs",row.startedAtEpochMs);out.put("updatedAtEpochMs",row.updatedAtEpochMs);out.put("notBeforeEpochMs",row.notBeforeEpochMs);out.put("result",row.result);
        var diagnostics=new LinkedHashMap<String,Object>();diagnostics.put("failure",row.reason);diagnostics.put("inputCodePoints",row.input.getOrDefault("codePoints",0));
        var records=new ArrayList<Map<String,Object>>();
        for(String id:requestIds(row)) {
            var record=new LinkedHashMap<>(calls.inspect(properties.getProjectKey(),properties.getAi().getSystemUserId(),id));
            var authority=map(row.checkpoint.get("authority"));if(id.equals(authority.get("requestId")))for(String key:List.of("executionState","executionEndedAtEpochMs"))if(authority.containsKey(key))record.putIfAbsent(key,authority.get(key));
            records.add(record);
        }
        diagnostics.put("attemptHistory",row.checkpoint.getOrDefault("attemptHistory",List.of()));
        diagnostics.put("inputPreparationHistory",row.checkpoint.getOrDefault("inputPreparationHistory",List.of()));
        diagnostics.put("input",row.input);
        diagnostics.put("calls",records);out.put("diagnostics",diagnostics);return out;
    }
    static List<String> requestIds(LabTrainingTask task) {
        var ids=new LinkedHashSet<>(strings(task.checkpoint.get("requestHistory")));
        String original=text(task.checkpoint.get("originalRequestId"));if(!original.isBlank())ids.add(original);
        String generated=text(map(task.checkpoint.get("playerCheckpoint")).get("originalRequestId"));if(!generated.isBlank())ids.add(generated);
        if(task.requestId!=null)ids.add(task.requestId);return List.copyOf(ids);
    }
    private String nextStep(LabTrainingRun run) {
        if("PAUSED".equals(run.status))return "REPRESENTATIVE_CASE_READY".equals(run.reason)?"代表案例已保存，恢复后继续课程":"已保留进度，等待恢复";
        if(Set.of("COMPLETED","STOPPED").contains(run.status))return run.candidateStrategyVersionId==null?"查看结果，本轮没有可发布的策略":"查看评测与策略差异，手动选择人格试用";
        return switch(run.stage){case "PRACTICE"->"完成局面练习与针对性教学";case "MERGE"->"合并通过校验的策略修改";case "REHEARSAL"->"复练开发案例，不计入独立效果";case "HOLDOUT"->"完成独立保留题对照";case "GAMES"->"完成两对真实九人对局";default->"生成本轮结果";};
    }
}
