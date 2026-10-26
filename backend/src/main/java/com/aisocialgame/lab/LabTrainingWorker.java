package com.aisocialgame.lab;

import com.aisocialgame.engine.v2.werewolf.WerewolfRuleSet;
import com.aisocialgame.exception.ApiException;
import com.aisocialgame.integration.grpc.client.AiGrpcClient;
import com.aisocialgame.integration.grpc.dto.*;
import com.aisocialgame.service.ai.config.AiModelSelection;
import com.aisocialgame.service.ai.v2.*;
import com.aisocialgame.service.safety.*;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.function.Consumer;
import static com.aisocialgame.engine.v2.RuleSupport.*;

/** One durable decision at a time, using the coordinator's single inference worker. */
@Service
public class LabTrainingWorker {
    private final LabTrainingService s;
    private final AiTurnGenerator generator;
    private final WerewolfRuleSet rules;
    private final AiGrpcClient client;
    private final LabTrainingInput input;
    private static final Set<String> TERMINAL=Set.of("SUCCEEDED","FAILED","SKIPPED");
    public LabTrainingWorker(LabTrainingService s,AiTurnGenerator generator,WerewolfRuleSet rules,AiGrpcClient client,LabTrainingInput input) {
        this.s=s;this.generator=generator;this.rules=rules;this.client=client;this.input=input;
    }
    private record Claim(LabTrainingRun run,LabTrainingCase item,LabTrainingTask task) {}
    public boolean process(String experimentId) {
        var experiment=s.experiments.findById(experimentId).orElseThrow();
        String id=text(experiment.configuration.get("activeTrainingRunId"));if(id.isBlank())return false;
        var run=s.runs.findById(id).orElseThrow();if(!Set.of("RUNNING","PAUSED").contains(run.status))return false;
        if("RUNNING".equals(run.status))try {advance(id);}catch(RuntimeException failure) {
            s.mutate(id,(r,e)->{r.status="PAUSED";r.reason="TRAINING_STAGE_FAILED";r.assessment.put("stageFailure",Map.of("stage",r.stage,"type",failure.getClass().getSimpleName(),"message",Objects.toString(failure.getMessage(),"")));e.status="PAUSED";e.reason=r.reason;});return false;
        }
        Claim claim=claim(id);if(claim==null)return false;
        try(var lab=LabCallContext.open(Map.of("experimentId",experimentId,"matchId",claim.task.id),
                "TEACH".equals(claim.task.kind)?"TRAINING_COACH":"JUDGE".equals(claim.task.kind)?"TRAINING_JUDGE":"TRAINING_PLAYER");
            var scope=AiCallScope.open(AiSafetyContext.source("AI_PLAYER").room(claim.task.id,"werewolf").persona(claim.item.personaId))) {
            if(player(claim.task)) play(claim);else teach(claim);
        } catch(AiTurnGenerator.GenerationDeferred deferred) {
            change(claim,t->{t.checkpoint.put("playerCheckpoint",deferred.checkpoint());t.requestId=text(deferred.checkpoint().get("originalRequestId"));});
            if(Set.of(LabBudgetService.EXHAUSTED,"LAB_EXPERIMENT_PAUSED").contains(deferred.getMessage())) {
                change(claim,t->{t.status="WAITING";t.reason=deferred.getMessage();t.leaseUntilEpochMs=0;});
                s.mutate(id,(r,e)->{r.status="PAUSED";r.reason=deferred.getMessage();e.status="PAUSED";e.reason=r.reason;});
            } else if("LAB_CALL_RETRIES_EXHAUSTED".equals(deferred.getMessage()))finish(claim,"FAILED",deferred.getMessage(),Map.of());
            else waitForOriginal(claim,deferred.getMessage(),false);
        } catch(AiTurnGenerator.GenerationFailure failed) {
            change(claim,t->t.result.put("diagnostics",failed.diagnostics()));
            var current=s.tasks.findById(claim.task.id).orElseThrow();
            if(!text(current.requestId).isBlank() && !flag(failed.diagnostics().get("usageComplete"))) waitForOriginal(claim,failed.getMessage(),false);
            else finish(claim,"FAILED",failed.getMessage(),Map.of("diagnostics",failed.diagnostics()));
        } catch(ApiException failed) {
            if(Set.of(LabBudgetService.EXHAUSTED,"LAB_EXPERIMENT_PAUSED").contains(text(failed.getCode()))) {
                change(claim,t->{t.status="WAITING";t.reason=failed.getCode();t.leaseUntilEpochMs=0;});
                s.mutate(id,(r,e)->{r.status="PAUSED";r.reason=failed.getCode();e.status="PAUSED";e.reason=r.reason;});
            } else handleModelFailure(claim,failed);
        } catch(RuntimeException failed) {
            // An unclassified local error never authorizes another inference.
            change(claim,t->{t.status="BLOCKED";t.reason="TRAINING_LOCAL_ERROR";t.leaseUntilEpochMs=0;t.result.put("localErrorType",failed.getClass().getSimpleName());});
            s.mutate(id,(r,e)->r.reason="TRAINING_LOCAL_ERROR");
        }
        return true;
    }
    private Claim claim(String id) {
        final Claim[] result={null};
        s.mutate(id,(run,experiment)->{
            if(!Set.of("RUNNING","PAUSED").contains(run.status) || Set.of("GAMES","REPORT").contains(run.stage))return;
            var rows=s.tasks.findByRunIdOrderByOrdinalAsc(id);long now=System.currentTimeMillis();
            String representative=flag(run.configuration.get("representativeOnly"))?s.cases.findByRunIdOrderByOrdinalAsc(id).stream()
                    .filter(c->"DEVELOPMENT".equals(c.purpose)).map(c->c.id).findFirst().orElse(""):"";
            for(var task:rows) {
                if(flag(run.configuration.get("representativeOnly")) && !task.caseId.equals(representative))continue;
                if(!Set.of("PENDING","WAITING","RUNNING").contains(task.status) || task.notBeforeEpochMs>now || task.leaseUntilEpochMs>now)continue;
                boolean recovering=existingResult(task);
                if(!"RUNNING".equals(run.status) && !recovering)continue;
                if(!dependencies(task,rows)){if("BLOCKED".equals(task.status))run.reason=task.reason;continue;}
                // UNKNOWN executions hold their model lane even after the local lease expires.
                boolean laneBusy=rows.stream().anyMatch(other->!other.id.equals(task.id) && player(other)==player(task)
                        && Set.of("RUNNING","WAITING","BLOCKED").contains(other.status) && !text(other.requestId).isBlank()
                        && !"TERMINATED".equals(other.checkpoint.get("executionState")) && !"NOT_STARTED".equals(other.checkpoint.get("executionState")));
                if(laneBusy && !recovering)continue;
                if(!recovering)try {s.budgets.requireRemaining(experiment);}catch(ApiException exhausted){run.status="PAUSED";run.reason=exhausted.getCode();experiment.status="PAUSED";experiment.reason=run.reason;continue;}
                var item=s.cases.findById(task.caseId).orElseThrow();
                if(task.input.isEmpty()) prepare(run,item,task,rows);
                if("FAILED".equals(task.status)) {s.tasks.save(task);continue;}
                task.status="RUNNING";task.reason=null;task.updatedAtEpochMs=now;
                if(task.startedAtEpochMs==null)task.startedAtEpochMs=now;
                task.leaseUntilEpochMs=now+(player(task)?180_000:LabCoachLimits.LEASE_MS);
                s.tasks.saveAndFlush(task);result[0]=new Claim(run,item,task);return;
            }
        });return result[0];
    }
    private boolean dependencies(LabTrainingTask task,List<LabTrainingTask> rows) {
        String dependency="TEACH".equals(task.kind)?"PRACTICE":"JUDGE".equals(task.kind)?"HOLDOUT":"";
        if(dependency.isBlank())return true;
        var previous=rows.stream().filter(t->task.caseId.equals(t.caseId)&&dependency.equals(t.kind)).toList();
        if(previous.size()!=("JUDGE".equals(task.kind)?2:1)) {
            task.status="BLOCKED";task.reason="DEPENDENCY_MISSING";task.updatedAtEpochMs=System.currentTimeMillis();s.tasks.save(task);return false;
        }
        if(previous.stream().anyMatch(t->Set.of("FAILED","SKIPPED").contains(t.status))) {
            task.status="SKIPPED";task.reason="DEPENDENCY_FAILED";task.updatedAtEpochMs=System.currentTimeMillis();s.tasks.save(task);return false;
        }
        return previous.stream().allMatch(t->"SUCCEEDED".equals(t.status));
    }
    private void prepare(LabTrainingRun run,LabTrainingCase item,LabTrainingTask task,List<LabTrainingTask> rows) {
        if(player(task)) {
            var observation=s.json.convertValue(item.data.get("observation"),VisibleObservation.class);
            var memory=new LinkedHashMap<>(observation.memory());memory.remove("approved");memory.remove("approvedExperience");memory.remove("strategy");
            memory.put("lab",Map.of("experimentId",run.experimentId,"matchId",task.id,"labRunMode","HOLDOUT".equals(task.kind)?"EVALUATION":"TRAINING",
                    "trainingV2",true,"scenarioEvaluation",true));
            var model=map(item.data.get("modelSelection"));
            if(model.isEmpty())model=maps(run.configuration.get("roster")).stream().filter(p->item.participantId.equals(LabService.participantId(p))).map(p->map(p.get("modelSelection"))).findFirst().orElseThrow();
            var snapshot="CANDIDATE".equals(task.variant)?s.strategies.strategySnapshot(run.candidateStrategyVersionId):map(run.configuration.get("baseStrategy"));
            var selected=LabStrategyService.select(snapshot,text(observation.self().get("role")),observation.phase(),observation.rules(),model,observation.turnKind());
            if(!selected.isEmpty())memory.put("strategy",selected);
            observation=new VisibleObservation(observation.gameId(),observation.instanceId(),observation.phase(),observation.round(),observation.actorId(),observation.turnKind(),
                    observation.self(),observation.players(),observation.rules(),observation.events(),observation.privateFacts(),observation.legalActions(),observation.persona(),memory,observation.knowledge());
            task.input.put("observation",s.json.convertValue(observation,Map.class));task.input.put("usedStrategy",selected);
            task.checkpoint.put("playerCheckpoint",new LinkedHashMap<>(Map.of("modelSelection",model)));
            task.checkpoint.put("remainingMillis","HOLDOUT".equals(task.kind)?50_000:150_000);
            return;
        }
        var payload=new LinkedHashMap<String,Object>();payload.put("observation",item.data.get("observation"));
        payload.put("validEvidenceIds",item.data.get("validEvidenceIds"));payload.put("caseRubric",item.data.get("expectedRubric"));
        if("TEACH".equals(task.kind)) {
            payload.put("sourceAction",item.data.get("actualAction"));payload.put("actualPrompt",item.data.get("actualPrompt"));
            payload.put("actualPromptStatus",item.data.get("originalPromptStatus"));payload.put("outcomeTruth",item.data.get("outcomeTruth"));
            payload.put("source",item.data.get("source"));payload.put("objective",item.data.get("focus"));
            var practice=rows.stream().filter(t->item.id.equals(t.caseId)&&"PRACTICE".equals(t.kind)).findFirst().orElseThrow();
            payload.put("practice",practice.result);payload.put("practicePrompt",practice.input.get("actualPrompt"));
            var role=text(map(map(item.data.get("observation")).get("self")).get("role"));
            payload.put("previousCards",maps(map(map(run.configuration.get("baseStrategy")).get("content")).get("cards")).stream()
                    .filter(c->Set.of("GENERAL",role).contains(text(c.get("role")))).toList());
            task.checkpoint.put("dependencies",List.of(practice.id));
        } else {
            var answers=rows.stream().filter(t->item.id.equals(t.caseId)&&"HOLDOUT".equals(t.kind)).toList();
            boolean candidateFirst=new Random(LabGameConfig.seed(seed(run),"judge:"+item.id)).nextBoolean();
            var mapping=Map.of("A",candidateFirst?"CANDIDATE":"BASELINE","B",candidateFirst?"BASELINE":"CANDIDATE");
            var anonymous=new LinkedHashMap<String,Object>();
            for(String label:List.of("A","B")) {
                var response=answers.stream().filter(t->mapping.get(label).equals(t.variant)).findFirst().orElseThrow();
                var answer=new LinkedHashMap<String,Object>();for(String key:List.of("action","speech","evidenceEventIds"))answer.put(key,response.result.get(key));anonymous.put(label,answer);
            }
            payload.put("answers",anonymous);task.checkpoint.put("variantByLabel",mapping);task.checkpoint.put("dependencies",answers.stream().map(t->t.id).toList());
        }
        var prepared=input.prepare("TEACH".equals(task.kind)?LabTrainingTeaching.TEACH_SYSTEM:LabTrainingTeaching.JUDGE_SYSTEM,payload);
        task.input.put("payload",payload);task.input.put("encoded",prepared.encoded());task.input.put("codePoints",prepared.codePoints());
        task.input.put("messages",prepared.messages().stream().map(m->Map.of("role",m.role(),"content",m.content())).toList());
        if(!prepared.ready()){task.status="FAILED";task.reason="MATERIAL_TOO_LARGE";}
    }
    private void play(Claim claim) {
        var t=claim.task;var observation=s.json.convertValue(t.input.get("observation"),VisibleObservation.class);
        var checkpoint=map(t.checkpoint.get("playerCheckpoint"));long remaining=((Number)t.checkpoint.getOrDefault("remainingMillis",150_000)).longValue();
        long started=System.nanoTime();boolean recovery=!text(checkpoint.get("originalRequestId")).isBlank() && !"AI_REQUEST_NOT_SENT".equals(checkpoint.get("lastCallCode"));
        AiTurnDecision decision;
        try {decision=generator.generate(rules,observation,t.id,remaining,()->true,checkpoint,saved->change(claim,row->{
            row.checkpoint.put("playerCheckpoint",saved);row.requestId=text(saved.get("originalRequestId"));
            if(saved.containsKey("actionExecutionRemainingMs"))row.checkpoint.put("remainingMillis",saved.get("actionExecutionRemainingMs"));
            for(String key:List.of("actualPrompt","encoded","codePoints"))if(saved.containsKey(key))row.input.put(key,saved.get(key));
            remember(row,row.requestId);
        }));}catch(AiTurnGenerator.GenerationDeferred deferred){
            if(!recovery && !"AI_REQUEST_NOT_SENT".equals(deferred.checkpoint().get("lastCallCode"))) {
                long left=Math.max(0,remaining-(System.nanoTime()-started)/1_000_000);change(claim,row->row.checkpoint.put("remainingMillis",left));
            }throw deferred;
        }
        var result=new LinkedHashMap<String,Object>();result.put("action",s.json.convertValue(decision.action(),Map.class));result.put("speech",decision.speech());
        result.put("evidenceEventIds",decision.evidenceEventIds());result.put("diagnostics",decision.diagnostics());result.put("usedStrategy",t.input.get("usedStrategy"));
        result.put("programChecks",Map.of("legalAction","PASS","evidenceBoundary","PASS","skillResources","PASS","requestCompleted",true));
        boolean late=!recovery && (System.nanoTime()-started)/1_000_000>remaining;
        if("HOLDOUT".equals(t.kind)) {
            var current=s.tasks.findById(t.id).orElseThrow();
            if(!text(current.requestId).isBlank()) {
                var authority=s.calls.inspect(s.properties.getProjectKey(),s.properties.getAi().getSystemUserId(),current.requestId);
                var elapsed=map(authority.get("timing")).get("totalMs");late|=elapsed instanceof Number n && n.longValue()>45_000;
            }
        }
        if(late)result.put("programChecks",Map.of("legalAction","PASS","evidenceBoundary","PASS","skillResources","PASS","requestCompleted",true,"executionWindow","FAIL"));
        finish(claim,late?"FAILED":"SUCCEEDED",late?"TIMEOUT":null,result);
    }
    private void teach(Claim claim) {
        var row=claim.task;AiChatResult response;
        if(row.checkpoint.containsKey("response")) {
            var saved=map(row.checkpoint.get("response"));response=new AiChatResult(text(saved.get("content")),text(saved.get("modelKey")),number(saved.get("promptTokens"),0),number(saved.get("completionTokens"),0),text(saved.get("finishReason")),null,null,null);
        } else {
            String original=text(row.checkpoint.get("originalRequestId"));
            String requestId=original.isBlank()?LabCallRecovery.requestId("lab-training:"+row.id+":"+row.attempt,row.checkpoint):original;
            change(claim,t->{t.requestId=requestId;t.checkpoint.put("originalRequestId",requestId);remember(t,requestId);});
            if(!original.isBlank())response=client.recoverChatCompletions(s.properties.getProjectKey(),s.properties.getAi().getSystemUserId(),requestId);
            else response=client.chatCompletions(s.properties.getProjectKey(),s.properties.getAi().getSystemUserId(),"training:"+row.id,
                    AiModelSelection.fromMap(map(claim.run.configuration.get("coach"))),maps(row.input.get("messages")).stream().map(m->new AiChatMessageDto(text(m.get("role")),text(m.get("content")))).toList(),requestId,LabCoachLimits.TOTAL_SECONDS);
            var saved=new LinkedHashMap<String,Object>();saved.put("content",response.content());saved.put("modelKey",response.modelKey());saved.put("promptTokens",response.promptTokens());saved.put("completionTokens",response.completionTokens());saved.put("finishReason",response.finishReason());
            change(claim,t->t.checkpoint.put("response",saved));
        }
        Map<String,Object> parsed=LabCoachService.parse(response,s.json);
        Map<String,Object> checked="TEACH".equals(row.kind)?LabTrainingTeaching.validateTeach(parsed,claim.item,map(claim.run.configuration.get("baseStrategy")),row.id):LabTrainingTeaching.validateJudge(parsed,claim.item);
        if("TEACH".equals(row.kind)) {
            for(var change:maps(checked.get("changes")))if(!"RETIRE".equals(change.get("operation"))) {
                var compatibility=Map.<String,Object>of("gameId","werewolf","rules",claim.run.configuration.get("rules"),"modelSelection",map(maps(claim.run.configuration.get("roster")).getFirst().get("modelSelection")));
                LabStrategyService.requireRuntimeSize(map(change.get("card")),compatibility);
            }
        }
        var result=new LinkedHashMap<>(checked);if("JUDGE".equals(row.kind))result.put("variantByLabel",row.checkpoint.get("variantByLabel"));
        result.put("rawReply",response.content());finish(claim,"SUCCEEDED",null,result);
    }
    private void handleModelFailure(Claim claim,ApiException failure) {
        String code=text(failure.getCode());var row=s.tasks.findById(claim.task.id).orElseThrow();
        if(Set.of("AI_REQUEST_NOT_SENT","AI_REQUEST_NOT_PREPARED").contains(code)) {
            change(claim,t->{
                var normalized=new ApiException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,failure.getMessage(),"AI_REQUEST_NOT_SENT",failure.getDetails());
                String reason=LabCallRecovery.defer(t.checkpoint,normalized,System.currentTimeMillis());
                t.checkpoint.remove("originalRequestId");t.checkpoint.put("executionState","NOT_STARTED");t.requestId=null;t.leaseUntilEpochMs=0;
                t.status=LabCallRecovery.blocked(reason)?"FAILED":"WAITING";t.reason=reason;t.notBeforeEpochMs=((Number)t.checkpoint.getOrDefault("retryAfterEpochMs",0L)).longValue();
            });return;
        }
        if(LabCallRecovery.recoverable(code) || row.checkpoint.containsKey("response")) {
            waitForOriginal(claim,code.isBlank()?"TRAINING_OUTPUT_INVALID":code,true);return;
        }
        if(!text(row.requestId).isBlank())waitForOriginal(claim,code.isBlank()?"TRAINING_CALL_FAILED":code,true);
        else finish(claim,"FAILED",code.isBlank()?"TRAINING_CALL_FAILED":code,Map.of());
    }
    private void waitForOriginal(Claim claim,String reason,boolean allowRedo) {
        var task=s.tasks.findById(claim.task.id).orElseThrow();
        var authority=text(task.requestId).isBlank()?Map.<String,Object>of():s.calls.inspectExecution(s.properties.getProjectKey(),s.properties.getAi().getSystemUserId(),task.requestId);
        String execution=text(authority.getOrDefault("executionState","UNKNOWN"));long now=System.currentTimeMillis();
        boolean terminated="TERMINATED".equals(execution) && authority.get("executionEndedAtEpochMs") instanceof Number;
        boolean retry=allowRedo && "TEACH".equals(task.kind) && terminated && task.paidRetries<1;
        s.mutate(claim.run.id,(run,experiment)->{
            var t=s.tasks.lock(task.id).orElseThrow();if(TERMINAL.contains(t.status))return;
            t.checkpoint.put("executionState",execution);t.checkpoint.put("authority",authority);t.leaseUntilEpochMs=0;
            if(retry && number(run.configuration.get("paidRetriesUsed"),0)<2) {
                var history=maps(t.checkpoint.get("attemptHistory"));var previous=new LinkedHashMap<String,Object>();previous.put("requestId",t.requestId);previous.put("reason",reason);previous.put("authority",authority);previous.put("response",t.checkpoint.get("response"));previous.put("input",LabStrategyService.copy(t.input));history.add(previous);
                t.checkpoint.put("attemptHistory",history);t.checkpoint.put("replacesRequestId",t.requestId);
                var payload=new LinkedHashMap<>(map(t.input.get("payload")));payload.put("correction",reason);
                if(t.checkpoint.containsKey("response"))payload.put("previousReply",map(t.checkpoint.get("response")).get("content"));
                var prepared=input.prepare(LabTrainingTeaching.TEACH_SYSTEM,payload);
                if(!prepared.ready()){t.status="FAILED";t.reason="REPAIR_MATERIAL_TOO_LARGE";}
                else {
                    t.input.put("payload",payload);t.input.put("encoded",prepared.encoded());t.input.put("codePoints",prepared.codePoints());t.input.put("messages",prepared.messages().stream().map(m->Map.of("role",m.role(),"content",m.content())).toList());
                    t.paidRetries++;t.attempt++;run.configuration.put("paidRetriesUsed",number(run.configuration.get("paidRetriesUsed"),0)+1);
                    for(String key:List.of("originalRequestId","response","transportAttempt","waitingSinceEpochMs","retryAfterEpochMs","lastCallState","lastCallCode"))t.checkpoint.remove(key);
                    t.requestId=null;t.status="PENDING";t.reason="PAID_REDO_WAIT";t.notBeforeEpochMs=now+60_000;
                }
            } else if(terminated && (!"SETTLED".equals(authority.get("state")) || t.checkpoint.containsKey("response"))) {t.status="FAILED";t.reason=reason;}
            else {
                t.checkpoint.putIfAbsent("waitingSinceEpochMs",now);long since=((Number)t.checkpoint.get("waitingSinceEpochMs")).longValue();
                t.status=now-since>=LabCallRecovery.MAX_WAIT_MS?"BLOCKED":"WAITING";t.reason="BLOCKED".equals(t.status)?"LAB_CALL_NO_PROGRESS":reason;t.notBeforeEpochMs=now+5_000;
                if("BLOCKED".equals(t.status))run.reason="LAB_CALL_NO_PROGRESS";
            }
            t.updatedAtEpochMs=now;s.tasks.saveAndFlush(t);
        });
    }
    private void finish(Claim claim,String status,String reason,Map<String,Object> result) {
        change(claim,t->{t.result.putAll(result);t.status=status;t.reason=reason;t.leaseUntilEpochMs=0;t.checkpoint.remove("waitingSinceEpochMs");});
    }
    private void change(Claim claim,Consumer<LabTrainingTask> operation) {
        s.mutate(claim.run.id,(run,experiment)->{
            var row=s.tasks.lock(claim.task.id).orElseThrow();if(TERMINAL.contains(row.status))return;
            operation.accept(row);row.updatedAtEpochMs=System.currentTimeMillis();s.tasks.saveAndFlush(row);
        });
    }
    private static void remember(LabTrainingTask t,String request) {
        if(request==null||request.isBlank())return;var ids=new LinkedHashSet<>(strings(t.checkpoint.get("requestHistory")));ids.add(request);t.checkpoint.put("requestHistory",List.copyOf(ids));
    }
    private static boolean player(LabTrainingTask t){return Set.of("PRACTICE","REHEARSAL","HOLDOUT").contains(t.kind);}
    private static boolean existingResult(LabTrainingTask t) {
        if(t.checkpoint.containsKey("response"))return true;
        var checkpoint=player(t)?map(t.checkpoint.get("playerCheckpoint")):t.checkpoint;
        return !text(checkpoint.get("originalRequestId")).isBlank() && !"AI_REQUEST_NOT_SENT".equals(checkpoint.get("lastCallCode"));
    }
    private static long seed(LabTrainingRun run){return ((Number)run.configuration.getOrDefault("randomSeed",0L)).longValue();}
    private void advance(String id) {
        s.mutate(id,(run,experiment)->{
            if(!"RUNNING".equals(run.status))return;
            var rows=s.tasks.findByRunIdOrderByOrdinalAsc(id);var material=s.cases.findByRunIdOrderByOrdinalAsc(id);
            if("PRACTICE".equals(run.stage)) {
                if(flag(run.configuration.get("representativeOnly"))) {
                    var first=material.stream().filter(c->"DEVELOPMENT".equals(c.purpose)).findFirst().orElseThrow();
                    var representative=rows.stream().filter(t->first.id.equals(t.caseId)&&Set.of("PRACTICE","TEACH").contains(t.kind)).toList();
                    if(representative.size()==2 && representative.stream().allMatch(t->TERMINAL.contains(t.status))) {
                        run.status="PAUSED";run.reason=representative.stream().allMatch(t->"SUCCEEDED".equals(t.status))?"REPRESENTATIVE_CASE_READY":"REPRESENTATIVE_CASE_FAILED";experiment.reason=run.reason;return;
                    }
                }
                if(rows.stream().anyMatch(t->!TERMINAL.contains(t.status)))return;
                run.stage="MERGE";
            }
            if("MERGE".equals(run.stage)) {
                var merged=LabTrainingTeaching.merge(map(run.configuration.get("baseStrategy")),rows);run.assessment.put("conflicts",merged.conflicts());
                if(merged.applied()==0){complete(run,experiment,"NO_VALID_STRATEGY_UPDATE");return;}
                var version=s.strategies.createTrainingVersion(experiment,run,merged.content());run.candidateStrategyVersionId=version.id;
                if(!merged.conflicts().isEmpty())experiment.configuration.put("trainingPublicationBlocked",Map.of("strategyVersionId",version.id,"reason","UNRESOLVED_STRATEGY_CONFLICT"));
                int ordinal=1000;for(var item:material)if("DEVELOPMENT".equals(item.purpose))s.addTask(run,item,"REHEARSAL","CANDIDATE",ordinal++);
                run.stage="REHEARSAL";return;
            }
            if("REHEARSAL".equals(run.stage)) {
                if(rows.stream().filter(t->"REHEARSAL".equals(t.kind)).anyMatch(t->!TERMINAL.contains(t.status)))return;
                int ordinal=2000;for(var item:material)if("HOLDOUT".equals(item.purpose)) {
                    boolean first=new Random(LabGameConfig.seed(seed(run),"pair:"+item.id)).nextBoolean();
                    for(String variant:first?List.of("CANDIDATE","BASELINE"):List.of("BASELINE","CANDIDATE"))s.addTask(run,item,"HOLDOUT",variant,ordinal++);
                    s.addTask(run,item,"JUDGE",null,ordinal++);
                }
                run.stage="HOLDOUT";return;
            }
            if("HOLDOUT".equals(run.stage)) {
                if(rows.stream().filter(t->Set.of("HOLDOUT","JUDGE").contains(t.kind)).anyMatch(t->!TERMINAL.contains(t.status)))return;
                var byId=new HashMap<String,LabTrainingCase>();material.forEach(c->byId.put(c.id,c));run.assessment.putAll(LabTrainingTeaching.assess(rows,byId));
                if(number(run.assessment.get("candidateRuleErrors"),0)>0 || number(run.assessment.get("candidateEvidenceLeaks"),0)>0)
                    experiment.configuration.put("trainingPublicationBlocked",Map.of("strategyVersionId",run.candidateStrategyVersionId,"reason","CONFIRMED_RULE_OR_VIEW_ERROR"));
                if(!"IMPROVED".equals(run.assessment.get("status"))){complete(run,experiment,"HOLDOUT_"+run.assessment.get("status"));return;}
                run.stage="GAMES";
            }
            if("GAMES".equals(run.stage))games(run,experiment);
        });
    }
    private void games(LabTrainingRun run,LabExperiment experiment) {
        var evaluations=s.evaluations.findByExperimentIdOrderByCreatedAtDesc(experiment.id).stream().filter(e->run.id.equals(e.configuration.get("trainingRunId"))).toList();
        var active=evaluations.stream().filter(e->"RUNNING".equals(e.status)).findFirst();
        if(active.isPresent()) {
            if("PAUSED".equals(experiment.status)) {run.status="PAUSED";run.reason=Objects.toString(experiment.reason,"GAME_PAUSED");}return;
        }
        int completed=0;var outcomes=new ArrayList<Map<String,Object>>();
        for(var evaluation:evaluations) {
            var games=s.matches.findByEvaluationIdOrderBySequenceNumberAsc(evaluation.id);
            boolean valid="COMPLETED".equals(evaluation.status)&&games.size()==2&&games.stream().allMatch(m->"REVIEWED".equals(m.status)&&m.archiveId!=null&&m.reason==null);
            if(valid)completed++;outcomes.add(Map.of("evaluationId",evaluation.id,"complete",valid,"statistics",LabService.evaluationView(evaluation,games),"matches",games.stream().map(m->Map.of("id",m.id,"status",m.status,"variant",text(m.variant),"archiveId",text(m.archiveId),"reason",text(m.reason))).toList()));
        }
        run.assessment.put("completedGamePairs",completed);run.assessment.put("gamePairs",outcomes);
        if(evaluations.size()>completed){complete(run,experiment,"GAME_PAIR_INCOMPLETE");return;}
        if(completed>=2){complete(run,experiment,"PILOT_COMPLETED");return;}
        try{s.budgets.requireRemaining(experiment);}catch(ApiException e){run.status="PAUSED";run.reason=e.getCode();experiment.status="PAUSED";experiment.reason=run.reason;return;}
        var roster=maps(run.configuration.get("roster"));String persona=completed==0?"ai1":"ai2";
        var target=roster.stream().filter(p->persona.equals(text(p.get("personaId")))).findFirst().orElseThrow();
        var evaluation=new LabEvaluation();evaluation.experimentId=experiment.id;evaluation.agentId=text(target.get("agentId"));evaluation.participantId=LabService.participantId(target);
        evaluation.pairs=1;evaluation.randomSeed=LabGameConfig.seed(seed(run),"training-pair:"+completed);
        evaluation.configuration.put("gameId","werewolf");evaluation.configuration.put("trainingRunId",run.id);evaluation.configuration.put("roster",roster);evaluation.configuration.put("returnStatus","PAUSED");
        evaluation.configuration.put("initialStrategy",run.configuration.get("baseStrategy"));evaluation.configuration.put("candidateStrategy",s.strategies.strategySnapshot(run.candidateStrategyVersionId));
        evaluation.configuration.put("targetRole",completed==0?"VILLAGER":"WEREWOLF");evaluation.configuration.put("candidateFirst",new Random(evaluation.randomSeed).nextBoolean());
        s.evaluations.saveAndFlush(evaluation);experiment.status="RUNNING";experiment.reason=null;
    }
    private static void complete(LabTrainingRun run,LabExperiment experiment,String reason) {
        run.status="COMPLETED";run.stage="REPORT";run.reason=reason;experiment.status="PAUSED";experiment.reason=reason;
    }
}
