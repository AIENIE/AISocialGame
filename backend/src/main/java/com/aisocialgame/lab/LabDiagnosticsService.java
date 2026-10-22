package com.aisocialgame.lab;

import com.aisocialgame.engine.v2.undercover.UndercoverWordCatalog;
import com.aisocialgame.repository.*;
import com.aisocialgame.repository.credit.AiCreditReservationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static com.aisocialgame.engine.v2.RuleSupport.*;

@Service
@Transactional(readOnly=true)
public class LabDiagnosticsService {
    private final LabMatchRepository matches;
    private final AiTurnJobRepository jobs;
    private final LocalAiDebugTurnRepository debug;
    private final GameStateRepository states;
    private final AiCreditReservationRepository reservations;
    private final UndercoverWordCatalog words;

    public LabDiagnosticsService(LabMatchRepository matches,AiTurnJobRepository jobs,LocalAiDebugTurnRepository debug,
            GameStateRepository states,AiCreditReservationRepository reservations,UndercoverWordCatalog words) {
        this.matches=matches;this.jobs=jobs;this.debug=debug;this.states=states;this.reservations=reservations;this.words=words;
    }
    public Map<String,Object> inspect(String experimentId,String matchId) {
        var match=matches.findById(matchId).orElseThrow(LabService::missing);
        require(experimentId.equals(match.experimentId),"对局不属于此实验");
        String instance=match.archiveId==null?states.findById(match.roomId).map(state -> text(state.getData().get("archiveId"))).orElse(null):match.archiveId;
        var turns=instance==null?List.<com.aisocialgame.model.AiTurnJob>of():jobs.findByInstanceIdOrderByCreatedAtAsc(instance);
        var result=new LinkedHashMap<String,Object>();
        result.put("matchId",match.id);result.put("sequenceNumber",match.sequenceNumber);result.put("status",match.status);
        if(match.archiveId!=null) result.put("archiveId",match.archiveId);
        var failures=turns.stream().filter(job -> "FAILED".equals(job.getStatus())).toList();
        var failure=new LinkedHashMap<String,Object>();
        if(match.reason!=null) { failure.put("code",match.reason);failure.put("stage","REVIEW_FAILED".equals(match.status)?"REVIEW":"EXECUTION"); }
        if(!failures.isEmpty()) {
            var job=failures.getLast();var metrics=map(job.getDiagnostics().get("generation"));
            var flags=strings(metrics.get("qualityFlags"));
            String reason=text(job.getDiagnostics().get("terminalReason"));
            boolean eliminated="FORBIDDEN_WORD_ELIMINATED".equals(reason);
            failure.put("code",eliminated?reason:metrics.getOrDefault("failureCode",flags.isEmpty()?reason:flags.getFirst()));
            failure.put("stage",eliminated?"RULE":metrics.getOrDefault("failureStage","SUBMISSION_EXCEPTION".equals(reason)?"SUBMISSION":"GENERATION"));
            failure.put("jobId",job.getId());failure.put("actorId",job.getActorId());
            failure.put("actorName",text(map(job.getObservation().get("self")).get("displayName")));
            failure.put("round",job.getObservation().getOrDefault("round",0));failure.put("phase",text(job.getObservation().get("phase")));
            failure.put("modelKey",text(map(job.getDiagnostics().get("modelSelection")).get("modelKey")));
            debug.findById(job.getId()).ifPresent(record -> {
                var samples=map(record.payload.get("attempts"));
                var sample=samples.entrySet().stream().max(Comparator.comparingInt(entry -> number(entry.getKey(),-1))).map(entry -> map(entry.getValue())).orElse(Map.of());
                var action=map(map(sample.get("parsedOutput")).get("action"));
                if(!action.isEmpty()) failure.put("action",Map.of("type",text(action.get("type")),"content",text(action.get("content"))));
                if("undercover-v1.1".equals(job.getDiagnostics().get("promptVersion")) && "SUBMISSION_EXCEPTION".equals(reason) && "SPEAK".equals(action.get("type")))
                    states.findById(match.roomId).filter(state -> Objects.equals(match.archiveId,state.getData().get("archiveId"))).ifPresent(state -> {
                        String content=text(action.get("content")),own=text(map(job.getObservation().get("self")).get("word"));
                        boolean blank="BLANK".equals(map(job.getObservation().get("self")).get("role"));
                        if((blank || !words.containsForbidden(content,own)) && (words.containsForbidden(content,text(state.getData().get("civilianWord")))
                                || words.containsForbidden(content,text(state.getData().get("undercoverWord"))))) {
                            failure.put("code","LEGACY_WORD_SCOPE_REJECTION");failure.put("inferred",true);
                        }
                    });
            });
        }
        if(!failure.isEmpty()) result.put("failure",failure);
        var receipts=reservations.findByLabMatchIdOrderByUpdatedAtAsc(matchId);
        var byRequest=new HashMap<String,com.aisocialgame.model.credit.AiCreditReservation>();
        receipts.forEach(row -> byRequest.put(row.requestId,row));
        result.put("tasks",turns.stream().map(job -> {
            var row=new LinkedHashMap<String,Object>();row.put("jobId",job.getId());row.put("status",job.getStatus());
            row.put("reason",text(job.getDiagnostics().getOrDefault("waitingReason",job.getDiagnostics().get("terminalReason"))));row.put("kind",job.getKind());
            row.put("modelKey",text(map(job.getDiagnostics().get("modelSelection")).get("modelKey")));
            row.put("actorName",text(map(job.getObservation().get("self")).get("displayName")));
            var generation=map(job.getObservation().get("generationCheckpoint"));
            if(Set.of("SUCCEEDED","FAILED","DISCARDED").contains(job.getStatus())) generation=map(job.getDiagnostics().get("generation"));
            row.put("attempts",maps(generation.get("attempts")).stream().map(sample -> {
                var attempt=new LinkedHashMap<>(sample); var receipt=byRequest.get(text(sample.get("requestId")));
                if(receipt!=null) {
                    if(sample.containsKey("settlementState")) attempt.put("observedSettlementState",sample.get("settlementState"));
                    attempt.put("settlementState",receipt.state);
                }
                return attempt;
            }).toList());
            return row;
        }).toList());
        result.put("calls",receipts.stream().map(call -> {
            var row=new LinkedHashMap<String,Object>();row.put("requestId",call.requestId);row.put("state",call.state);
            row.put("billedPoints",call.billedPoints==null?0:call.billedPoints);
            row.put("heldPoints","HELD".equals(call.state)?call.reservedTemp+call.reservedPermanent:0);
            row.put("purpose",text(call.labPurpose));return row;
        }).toList());
        return result;
    }
}
