package com.aisocialgame.lab;

import com.aisocialgame.AiSocialGameApplication;
import com.aisocialgame.config.AppProperties;
import com.aisocialgame.exception.ApiException;
import com.aisocialgame.integration.grpc.client.AiGrpcClient;
import com.aisocialgame.integration.grpc.dto.*;
import com.aisocialgame.model.AiTurnJob;
import com.aisocialgame.repository.AiTurnJobRepository;
import com.aisocialgame.service.ai.config.*;
import com.aisocialgame.service.credit.BoundedAiClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.*;
import static com.aisocialgame.engine.v2.RuleSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(classes=AiSocialGameApplication.class,properties="spring.datasource.url=jdbc:h2:mem:werewolf-manual-review;DB_CLOSE_DELAY=-1;MODE=MySQL")
@ActiveProfiles("test")
class LabManualWerewolfReviewTest {
    @Autowired LabManualReviewService manual;
    @Autowired LabStrategyService strategies;
    @Autowired LabStrategyVersionRepository versions;
    @Autowired LabExperimentRepository experiments;
    @Autowired LabMatchRepository matches;
    @Autowired AiTurnJobRepository jobs;
    @Autowired LabRuntime runtime;
    @Autowired LabCoachService coach;
    @Autowired LabBudgetService budgets;
    @Autowired AppProperties properties;
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean AiGrpcClient client;
    @MockitoBean BoundedAiClient calls;
    @MockitoBean AiModelConfigService models;
    final LabRequests.Selection coachSelection=new LabRequests.Selection("fixture-coach",AiModelSelection.ThinkingMode.ENABLED,"MAX");
    final AiModelSelection playerSelection=new AiModelSelection(AiFunctionCode.WEREWOLF,"fixture-player",AiModelSelection.ThinkingMode.ENABLED,"LOW",1);

    @BeforeEach void stubs() {when(calls.inspect(anyString(),anyLong(),anyString())).thenReturn(Map.of("state","FIXTURE"));}

    @Test void failedAutomaticReviewCanBeRereviewedOnceUsingOriginalInputAndStaysPaused() throws Exception {
        var f=fixture();var failed=matches.findById(f.match.id).orElseThrow();failed.status="REVIEW_FAILED";failed.reason="LAB_REVIEW_INVALID";matches.saveAndFlush(failed);
        when(client.undercoverChatCompletions(anyString(),anyLong(),anyString(),any(AiModelSelection.class),anyList(),anyString(),anyInt())).thenReturn(response(f,"新增有依据的策略。"));
        var request=request();var created=manual.create(f.experiment.id,f.match.id,request);
        assertEquals(created.get("taskId"),manual.create(f.experiment.id,f.match.id,request).get("taskId"));
        assertTrue(manual.process(f.experiment.id));assertFalse(manual.process(f.experiment.id));
        var result=manual.inspect(f.experiment.id,f.match.id,request.operationId());assertEquals("SUCCEEDED",result.get("status"));
        assertEquals(f.initial.id,result.get("previousStrategyVersionId"));assertNotEquals(f.initial.id,result.get("publishedStrategyVersionId"));
        assertEquals(2,versions.findByExperimentIdOrderBySequenceNumberDesc(f.experiment.id).size());
        var messages=ArgumentCaptor.forClass(List.class);
        verify(client,times(1)).undercoverChatCompletions(anyString(),anyLong(),anyString(),any(AiModelSelection.class),messages.capture(),eq(text(created.get("requestId"))),eq(1200));
        var sent=(List<AiChatMessageDto>)messages.getValue();assertEquals(2,sent.size());
        var original=maps(map(f.match.snapshot.get("reviewRequest")).get("messages"));
        assertEquals(original.get(0).get("content"),sent.get(0).content());assertEquals(original.get(1).get("content"),sent.get(1).content());
        runtime.advance(f.experiment.id);assertEquals("PAUSED",experiments.findById(f.experiment.id).orElseThrow().status);
        assertEquals(1,matches.findByExperimentIdOrderBySequenceNumberDesc(f.experiment.id,org.springframework.data.domain.PageRequest.of(0,10)).getTotalElements());
        assertEquals("REVIEWED",matches.findById(f.match.id).orElseThrow().status);
        verify(client,times(1)).preflight(anyString(),anyLong(),any(),eq(LabCoachLimits.MAX_OUTPUT_TOKENS));
    }

    @Test void invalidReplyIsRetainedWithoutCorrectionOrChangingTheSharedHead() throws Exception {
        var f=fixture();when(client.undercoverChatCompletions(anyString(),anyLong(),anyString(),any(AiModelSelection.class),anyList(),anyString(),anyInt()))
                .thenReturn(new AiChatResult("{\"cards\":[{\"id\":\"missing-fields\"}]}","fixture-coach",10,5));
        var request=request();manual.create(f.experiment.id,f.match.id,request);manual.process(f.experiment.id);
        var result=manual.inspect(f.experiment.id,f.match.id,request.operationId());assertEquals("FAILED",result.get("status"));assertTrue(result.containsKey("response"));
        assertEquals(f.initial.id,experiments.findById(f.experiment.id).orElseThrow().configuration.get("strategyHeadId"));
        assertEquals(1,versions.findByExperimentIdOrderBySequenceNumberDesc(f.experiment.id).size());
        assertFalse(manual.process(f.experiment.id));verify(client,times(1)).undercoverChatCompletions(anyString(),anyLong(),anyString(),any(AiModelSelection.class),anyList(),anyString(),anyInt());
        assertEquals("PAUSED",experiments.findById(f.experiment.id).orElseThrow().status);
    }

    @Test void changedSharedHeadPreventsStalePublicationButKeepsThePaidReply() throws Exception {
        var f=fixture();when(client.undercoverChatCompletions(anyString(),anyLong(),anyString(),any(AiModelSelection.class),anyList(),anyString(),anyInt())).thenReturn(response(f,"原任务的新策略。"));
        var request=request();manual.create(f.experiment.id,f.match.id,request);
        var changed=experiments.findById(f.experiment.id).orElseThrow();
        var newer=strategies.createVersion(changed,f.match,"newer-"+UUID.randomUUID(),content(f.job,"已确认的另一个策略版本。"),compatibility());
        manual.process(f.experiment.id);var result=manual.inspect(f.experiment.id,f.match.id,request.operationId());
        assertEquals("FAILED",result.get("status"));assertTrue(result.containsKey("response"));
        assertEquals(newer.id,experiments.findById(f.experiment.id).orElseThrow().configuration.get("strategyHeadId"));
        assertEquals(2,versions.findByExperimentIdOrderBySequenceNumberDesc(f.experiment.id).size());
    }

    @Test void restartQueriesSamePendingRequestAndOnlyNotSentChangesTransportIdentity() throws Exception {
        var f=fixture();var request=request();var created=manual.create(f.experiment.id,f.match.id,request);
        when(client.undercoverChatCompletions(anyString(),anyLong(),anyString(),any(AiModelSelection.class),anyList(),anyString(),anyInt()))
                .thenThrow(new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"仍在执行","AI_RESULT_PENDING",Map.of("remoteState","RUNNING")))
                .thenReturn(response(f,"恢复原结果。"));
        manual.process(f.experiment.id);assertEquals("WAITING",manual.inspect(f.experiment.id,f.match.id,request.operationId()).get("status"));ready(f.match.id,request.operationId());
        var recreated=new LabManualReviewService(experiments,matches,coach,budgets,client,calls,properties,json,transactions);
        recreated.process(f.experiment.id);assertEquals("SUCCEEDED",manual.inspect(f.experiment.id,f.match.id,request.operationId()).get("status"));
        verify(client,times(2)).undercoverChatCompletions(anyString(),anyLong(),anyString(),any(AiModelSelection.class),anyList(),eq(text(created.get("requestId"))),eq(1200));

        reset(client);var second=fixture();var next=request();var secondTask=manual.create(second.experiment.id,second.match.id,next);
        when(client.undercoverChatCompletions(anyString(),anyLong(),anyString(),any(AiModelSelection.class),anyList(),anyString(),anyInt()))
                .thenThrow(new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"确认未发送","AI_REQUEST_NOT_SENT",Map.of("remoteState","RELEASED")))
                .thenReturn(response(second,"未发送后的有效结果。"));
        manual.process(second.experiment.id);ready(second.match.id,next.operationId());recreated.process(second.experiment.id);
        var result=manual.inspect(second.experiment.id,second.match.id,next.operationId());assertEquals("SUCCEEDED",result.get("status"));
        assertEquals(text(secondTask.get("requestId"))+":transport:1",result.get("requestId"));
        assertEquals(2,strings(result.get("requestIds")).size());
    }

    @Test void operationConflictAndMissingHistoricalInputNeverStartARequest() {
        var f=fixture();var request=request();manual.create(f.experiment.id,f.match.id,request);
        assertEquals("LAB_REVIEW_OPERATION_CONFLICT",assertThrows(ApiException.class,()->manual.create(f.experiment.id,f.match.id,
                new LabRequests.Review(request.operationId(),new LabRequests.Selection("different",AiModelSelection.ThinkingMode.DEFAULT,null)))).getCode());
        var missing=fixture();missing.match.snapshot.remove("reviewRequest");matches.saveAndFlush(missing.match);
        assertThrows(ApiException.class,()->manual.create(missing.experiment.id,missing.match.id,request()));
        verify(client,never()).undercoverChatCompletions(anyString(),anyLong(),anyString(),any(AiModelSelection.class),anyList(),anyString(),anyInt());
    }

    private LabRequests.Review request() {return new LabRequests.Review(UUID.randomUUID().toString(),coachSelection);}
    private record Fixture(LabExperiment experiment,LabMatch match,AiTurnJob job,LabStrategyVersion initial) {}
    private Fixture fixture() {
        var experiment=new LabExperiment();experiment.name="manual werewolf fixture";experiment.createdBy="fixture-admin";experiment.budgetPoints=3000;
        experiment.status="PAUSED";experiment.nextSequence=2;experiment.configuration.put("gameId","werewolf");experiment.configuration.put("rules",rules());
        experiment.configuration.put("coach",coachSelection.snapshot().toMap());experiments.saveAndFlush(experiment);
        var match=new LabMatch();match.experimentId=experiment.id;match.roomId=UUID.randomUUID().toString();match.archiveId="archive-"+UUID.randomUUID();match.sequenceNumber=1;match.status="FINISHED";
        match.snapshot.put("players",Map.of("actor",Map.of("participantId",UUID.randomUUID().toString(),"modelSelection",playerSelection.toMap())));
        match.snapshot.put("reviewRequest",Map.of("gameId","werewolf","roomId",match.roomId,"inputVersion","werewolf-review-v1","identityField","sharedStrategy",
                "selection",coachSelection.snapshot().toMap(),"messages",List.of(Map.of("role","system","content","historical werewolf system"),
                        Map.of("role","user","content","{\"previousStrategy\":{},\"historicalInput\":true}"),Map.of("role","assistant","content","old repair must not be sent"))));
        match=matches.saveAndFlush(match);
        var job=new AiTurnJob();job.setId(UUID.randomUUID().toString());job.setRoomId(match.roomId);job.setInstanceId(match.archiveId);job.setActorId("actor");job.setKind("SPEAK");job.setTurnKey(UUID.randomUUID().toString());job.setStatus("SUCCEEDED");
        job.setObservation(Map.of("self",Map.of("role","SEER"),"events",List.of(Map.of("eventId","visible-at-decision"))));jobs.saveAndFlush(job);
        var initial=strategies.createVersion(experiment,match,"initial-"+UUID.randomUUID(),content(job,"保留已有有依据的判断。"),compatibility());
        match.status="REVIEWED";match=matches.saveAndFlush(match);return new Fixture(experiment,match,job,initial);
    }
    private Map<String,Object> rules() {return Map.of("template","standard","playerCount",9,"witchRule","first_night","winCondition","side","hasLastWords","first_night");}
    private Map<String,Object> compatibility() {return Map.of("gameId","werewolf","rules",rules(),"modelSelection",playerSelection.toMap());}
    private Map<String,Object> content(AiTurnJob job,String advice) {return Map.of("cards",List.of(Map.of("id","general_fact","role","GENERAL","phases",List.of(),"condition","判断身份时","advice",advice,"exceptions","证据不足时保留疑问",
            "evidence",List.of(Map.of("jobId",job.getId(),"actorId",job.getActorId(),"eventIds",List.of("visible-at-decision"))))));}
    private AiChatResult response(Fixture f,String advice) throws Exception {return new AiChatResult(json.writeValueAsString(content(f.job,advice)),"fixture-coach",10,5);}
    private void ready(String matchId,String operationId) {
        var match=matches.findById(matchId).orElseThrow();var runs=map(match.snapshot.get("manualReviews"));var run=map(runs.get(operationId));
        run.remove("leaseUntilEpochMs");var recovery=map(run.get("recovery"));recovery.remove("retryAfterEpochMs");run.put("recovery",recovery);
        runs.put(operationId,run);match.snapshot.put("manualReviews",runs);matches.saveAndFlush(match);
    }
}
