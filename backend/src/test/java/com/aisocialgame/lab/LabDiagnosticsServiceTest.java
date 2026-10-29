package com.aisocialgame.lab;

import com.aisocialgame.model.*;
import com.aisocialgame.model.credit.AiCreditReservation;
import com.aisocialgame.repository.*;
import com.aisocialgame.repository.credit.AiCreditReservationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.aisocialgame.engine.v2.RuleSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LabDiagnosticsServiceTest {
    final LabMatchRepository matches=mock(LabMatchRepository.class);
    final AiTurnJobRepository jobs=mock(AiTurnJobRepository.class);
    final LocalAiDebugTurnRepository debug=mock(LocalAiDebugTurnRepository.class);
    final GameStateRepository states=mock(GameStateRepository.class);
    final AiCreditReservationRepository reservations=mock(AiCreditReservationRepository.class);
    final LabDiagnosticsService service=new LabDiagnosticsService(matches,jobs,debug,states,reservations);
    final LabMatch match=new LabMatch();

    @BeforeEach void fixture() {
        match.experimentId="experiment";match.roomId="room";match.archiveId="archive";match.sequenceNumber=1;
        match.status="REVIEW_FAILED";match.reason="AUTO_REVIEW_FAILED";
        match.snapshot.put("reviewRequestId","automatic-request");
        match.snapshot.put("reviewRequest",Map.of("selection",selection("automatic-coach","HIGH")));
        when(matches.findById(match.id)).thenReturn(Optional.of(match));
        when(jobs.findByInstanceIdOrderByCreatedAtAsc(match.archiveId)).thenReturn(List.of(playerFailure()));
        when(reservations.findByLabMatchIdOrderByUpdatedAtAsc(match.id)).thenReturn(List.of());
    }

    @Test void latestBlockedManualReviewOwnsFailureWhileBothUnknownCallsAndOldPlayerFailureRemainVisible() {
        var runs=new LinkedHashMap<String,Object>();
        runs.put("current",run("BLOCKED",1792997100000L,"current-task","manual-request","gpt-6.1-sol","MAX","AI_USAGE_UNKNOWN"));
        runs.put("older",run("FAILED",1792997000000L,"old-task","old-manual","old-coach","LOW","LAB_REVIEW_INVALID"));
        runs.put("nonfailure",run("SUCCEEDED",1792997200000L,"success-task","success-request","other-coach","LOW",""));
        match.snapshot.put("manualReviews",runs);
        when(reservations.findByLabMatchIdOrderByUpdatedAtAsc(match.id)).thenReturn(List.of(
                receipt("automatic-request","REVIEW"),receipt("manual-request","MANUAL_REVIEW")));

        var result=service.inspect(match.experimentId,match.id);var failure=map(result.get("failure"));
        assertEquals("REVIEW",failure.get("stage"));assertEquals("AI_USAGE_UNKNOWN",failure.get("code"));
        assertEquals("教练",failure.get("actorName"));assertEquals("gpt-6.1-sol",failure.get("modelKey"));
        assertEquals("ENABLED",failure.get("thinkingMode"));assertEquals("MAX",failure.get("thinkingEffort"));
        assertEquals("manual-request",failure.get("requestId"));assertEquals("current-task",failure.get("jobId"));
        assertEquals("current-task",failure.get("taskId"));assertFalse(failure.containsKey("actorId"));
        var tasks=maps(result.get("tasks"));assertEquals(1,tasks.size());
        assertEquals("player-job",tasks.getFirst().get("jobId"));assertEquals("FAILED",tasks.getFirst().get("status"));
        var calls=maps(result.get("calls"));assertEquals(2,calls.size());
        assertEquals(List.of("automatic-request","manual-request"),calls.stream().map(call -> call.get("requestId")).toList());
        assertTrue(calls.stream().allMatch(call -> "USAGE_UNKNOWN".equals(call.get("state")) && call.get("billedPoints")==null));
        verifyNoInteractions(debug,states);
    }

    @Test void failedManualReviewUsesItsSavedCoachWhenHistoricalRequestHasNoSelection() {
        var run=new LinkedHashMap<>(run("FAILED",1792997100000L,"manual-task","manual-original","saved-manual-coach","MAX","LAB_REVIEW_INVALID"));
        run.put("request",Map.of());
        match.snapshot.put("manualReviews",Map.of("manual",run));
        var failure=map(service.inspect(match.experimentId,match.id).get("failure"));
        assertEquals("saved-manual-coach",failure.get("modelKey"));assertEquals("manual-original",failure.get("requestId"));
        assertEquals("LAB_REVIEW_INVALID",failure.get("code"));assertEquals("REVIEW",failure.get("stage"));
    }

    @Test void automaticReviewUsesOriginalSelectionAndNeverInventsMissingModelOrTaskIdentity() {
        match.snapshot.put("coach",selection("different-match-coach","LOW"));
        var failure=map(service.inspect(match.experimentId,match.id).get("failure"));
        assertEquals("automatic-coach",failure.get("modelKey"));assertEquals("HIGH",failure.get("thinkingEffort"));
        assertEquals("automatic-request",failure.get("requestId"));assertEquals("AUTO_REVIEW_FAILED",failure.get("code"));
        assertEquals("REVIEW",failure.get("stage"));assertEquals("教练",failure.get("actorName"));
        assertFalse(failure.containsKey("jobId"),"automatic review has no saved task ID");
        match.snapshot.remove("reviewRequest");match.snapshot.remove("coach");
        assertFalse(map(service.inspect(match.experimentId,match.id).get("failure")).containsKey("modelKey"));
    }

    @Test void savedPlayerFailureIsReportedWithoutInferringDifferentCause() {
        match.status="ABORTED";match.reason="SUBMISSION_EXCEPTION";
        var job=playerFailure();job.getDiagnostics().put("promptVersion","undercover-v1.1");
        job.getDiagnostics().put("terminalReason","SUBMISSION_EXCEPTION");job.getDiagnostics().remove("generation");
        when(jobs.findByInstanceIdOrderByCreatedAtAsc(match.archiveId)).thenReturn(List.of(job));
        var record=new LocalAiDebugTurn();record.payload.put("attempts",Map.of("0",Map.of("parsedOutput",Map.of("action",Map.of("type","SPEAK","content","other word")))));
        when(debug.findById(job.getId())).thenReturn(Optional.of(record));
        var failure=map(service.inspect(match.experimentId,match.id).get("failure"));
        assertEquals("SUBMISSION_EXCEPTION",failure.get("code"));assertFalse(failure.containsKey("inferred"));
        assertEquals("SUBMISSION",failure.get("stage"));assertEquals("player-job",failure.get("jobId"));
        assertEquals("旧玩家",failure.get("actorName"));assertEquals("deepseek-flash",failure.get("modelKey"));
        assertEquals("other word",map(failure.get("action")).get("content"));
    }

    private AiTurnJob playerFailure() {
        var job=new AiTurnJob();job.setId("player-job");job.setActorId("player");job.setKind("SPEAK");job.setStatus("FAILED");
        job.setObservation(Map.of("self",Map.of("displayName","旧玩家","role","BLANK"),"phase","DAY_SPEAK","round",1));
        job.getDiagnostics().putAll(Map.of("terminalReason","AI_GENERATION_FAILED","modelSelection",selection("deepseek-flash","LOW"),
                "generation",Map.of("failureCode","PLAYER_TIMEOUT","failureStage","GENERATION")));
        return job;
    }
    private Map<String,Object> selection(String model,String effort) {return Map.of("modelKey",model,"thinkingMode","ENABLED","thinkingEffort",effort);}
    private Map<String,Object> run(String status,long created,String task,String request,String model,String effort,String reason) {
        return Map.of("status",status,"createdAtEpochMs",created,"taskId",task,"requestId",request,"reason",reason,
                "request",Map.of("selection",selection(model,effort)),"coach",selection(model,effort));
    }
    private AiCreditReservation receipt(String request,String purpose) {
        var receipt=new AiCreditReservation();receipt.requestId=request;receipt.labPurpose=purpose;receipt.state="USAGE_UNKNOWN";
        return receipt;
    }
}
