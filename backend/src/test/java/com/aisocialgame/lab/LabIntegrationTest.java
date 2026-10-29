package com.aisocialgame.lab;

import com.aisocialgame.AiSocialGameApplication;
import com.aisocialgame.integration.grpc.client.AiGrpcClient;
import com.aisocialgame.integration.grpc.dto.*;
import com.aisocialgame.model.*;
import com.aisocialgame.model.credit.CreditAccount;
import com.aisocialgame.repository.*;
import com.aisocialgame.repository.credit.*;
import com.aisocialgame.service.ai.config.*;
import com.aisocialgame.service.ai.v2.*;
import com.aisocialgame.service.credit.AiCallSettlementService;
import com.aisocialgame.service.v2.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import fireflychat.ai.v1.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.util.*;
import java.util.concurrent.*;
import static com.aisocialgame.engine.v2.RuleSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(classes=AiSocialGameApplication.class,properties="app.ai.budget-reconcile-delay-ms=600000")
@ActiveProfiles("test")
class LabIntegrationTest {
    @Autowired LabService service;
    @Autowired LabRuntime lab;
    @Autowired LabCoachService coach;
    @Autowired LabManualReviewService manualReviews;
    @Autowired LabExperimentRepository experiments;
    @Autowired LabMatchRepository matches;
    @Autowired LabExperienceRepository experiences;
    @Autowired LabEnrollmentRepository enrollments;
    @Autowired PersonaRepository personas;
    @Autowired RoomRepository rooms;
    @Autowired GameStateRepository states;
    @Autowired AiTurnJobRepository jobs;
    @Autowired V2GameService game;
    @Autowired AiTurnCoordinator turns;
    @Autowired LabBudgetService budgets;
    @Autowired LabDiagnosticsService diagnostics;
    @Autowired AiCallSettlementService escrow;
    @Autowired CreditAccountRepository accounts;
    @Autowired AiCreditReservationRepository reservations;
    @Autowired ObjectMapper json;
    @Autowired org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter httpAdapter;
    @Autowired com.aisocialgame.config.AppProperties properties;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean com.aisocialgame.service.RoomCodeGenerator codes;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired GameArchiveRepository archives;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean com.aisocialgame.websocket.PlayerConnectionService connections;
    @MockitoBean AiModelConfigService models;
    @MockitoBean AiGrpcClient client;
    final LabRequests.Selection selection=new LabRequests.Selection("fixture-model",AiModelSelection.ThinkingMode.DEFAULT,null);

    @BeforeEach void stopPreviousFixtures() {
        for(var experiment:experiments.findAll()) { experiment.status="STOPPED"; experiments.save(experiment); }
        for(var match:matches.findAll()) { match.status="ABORTED"; matches.save(match); }
    }
    @Test void manualMaxReviewIsIdempotentPublishesAllTenAndNeverStartsSecondGame() throws Exception {
        String id=reviewedTenPlayerExperiment();
        var match=matches.findByExperimentIdAndSequenceNumber(id,1).orElseThrow();
        var firstMessages=maps(map(match.snapshot.get("reviewRequest")).get("messages"));
        var previousHeads=map(experiments.findById(id).orElseThrow().configuration.get("experiences"));
        var request=new LabRequests.Review(UUID.randomUUID().toString(),new LabRequests.Selection("max-fixture",AiModelSelection.ThinkingMode.ENABLED,"MAX"));
        var task=manualReviews.create(id,match.id,request);
        assertEquals(task.get("taskId"),manualReviews.create(id,match.id,request).get("taskId"));
        assertEquals("LAB_REVIEW_OPERATION_CONFLICT",assertThrows(com.aisocialgame.exception.ApiException.class,
                ()->manualReviews.create(id,match.id,new LabRequests.Review(request.operationId(),selection))).getCode());
        assertThrows(com.aisocialgame.exception.ApiException.class,()->service.control(id,"resume"));
        assertTrue(manualReviews.process(id));lab.advance(id);assertFalse(manualReviews.process(id));
        var result=manualReviews.inspect(id,match.id,request.operationId());assertEquals("SUCCEEDED",result.get("status"));
        assertEquals(firstMessages,maps(map(result.get("request")).get("messages")));
        assertEquals("PAUSED",experiments.findById(id).orElseThrow().status);
        var versions=experiences.findByExperimentIdOrderByCreatedAtDesc(id,org.springframework.data.domain.PageRequest.of(0,30));
        assertEquals(20,versions.getTotalElements());
        var heads=map(experiments.findById(id).orElseThrow().configuration.get("experiences"));assertEquals(10,heads.size());
        for(var entry:heads.entrySet()) {
            var version=experiences.findById(text(map(entry.getValue()).get("experienceId"))).orElseThrow();
            assertEquals(2,version.sequenceNumber);assertEquals(map(previousHeads.get(entry.getKey())).get("experienceId"),version.previousId);
        }
        assertEquals(1,matches.findByExperimentIdOrderBySequenceNumberDesc(id,org.springframework.data.domain.PageRequest.of(0,20)).getTotalElements());
        verify(client,times(2)).undercoverChatCompletions(anyString(),anyLong(),anyString(),any(AiModelSelection.class),anyList(),anyString(),eq(1200));
    }

    @Test void invalidManualReviewKeepsAllHighHeadsAndDoesNotPayForCorrection() {
        String id=reviewedTenPlayerExperiment();var match=matches.findByExperimentIdAndSequenceNumber(id,1).orElseThrow();
        var heads=map(experiments.findById(id).orElseThrow().configuration.get("experiences"));
        var request=new LabRequests.Review(UUID.randomUUID().toString(),selection);manualReviews.create(id,match.id,request);
        when(client.undercoverChatCompletions(anyString(),anyLong(),anyString(),any(AiModelSelection.class),anyList(),anyString(),anyInt()))
                .thenReturn(new AiChatResult("{\"experiences\":[]}","fixture-model",10,5));
        manualReviews.process(id);assertFalse(manualReviews.process(id));lab.advance(id);
        var task=manualReviews.inspect(id,match.id,request.operationId());assertEquals("FAILED",task.get("status"));
        assertEquals("LAB_REVIEW_INVALID",task.get("reason"));assertNotNull(task.get("response"));
        assertEquals(heads,experiments.findById(id).orElseThrow().configuration.get("experiences"));
        assertEquals(10,experiences.findByExperimentIdOrderByCreatedAtDesc(id,org.springframework.data.domain.PageRequest.of(0,30)).getTotalElements());
        assertEquals("PAUSED",experiments.findById(id).orElseThrow().status);
        verify(client,times(2)).undercoverChatCompletions(anyString(),anyLong(),anyString(),any(AiModelSelection.class),anyList(),anyString(),anyInt());
    }

    @Test void manualReviewRestartRecoversSameRequestAndOnlyNotSentChangesTransportIdentity() {
        String id=reviewedTenPlayerExperiment();var match=matches.findByExperimentIdAndSequenceNumber(id,1).orElseThrow();
        var request=new LabRequests.Review(UUID.randomUUID().toString(),selection);var task=manualReviews.create(id,match.id,request);
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(manualReviews,"claim",id);
        assertFalse(manualReviews.process(id));
        readyManual(match.id,request.operationId());
        when(client.undercoverChatCompletions(anyString(),anyLong(),anyString(),any(AiModelSelection.class),anyList(),anyString(),anyInt()))
                .thenThrow(new com.aisocialgame.exception.ApiException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"pending","AI_RESULT_PENDING",Map.of("remoteState","RUNNING")));
        manualReviews.process(id);
        assertEquals(task.get("requestId"),manualReviews.inspect(id,match.id,request.operationId()).get("requestId"));
        readyManual(match.id,request.operationId());stubCoach();manualReviews.process(id);
        assertEquals("SUCCEEDED",manualReviews.inspect(id,match.id,request.operationId()).get("status"));
        verify(client,times(2)).undercoverChatCompletions(anyString(),anyLong(),anyString(),any(AiModelSelection.class),anyList(),eq(text(task.get("requestId"))),eq(1200));
    }
    private void readyManual(String matchId,String operationId) {
        var match=matches.findById(matchId).orElseThrow();var runs=map(match.snapshot.get("manualReviews"));var run=map(runs.get(operationId));
        run.remove("leaseUntilEpochMs");var recovery=map(run.get("recovery"));recovery.remove("retryAfterEpochMs");run.put("recovery",recovery);
        runs.put(operationId,run);match.snapshot.put("manualReviews",runs);matches.saveAndFlush(match);
    }
    private String reviewedTenPlayerExperiment() {
        var agent=service.saveAgent(null,new LabRequests.Agent("Ten player fixture",personas.findAll().getFirst().getId(),selection));
        var participants=java.util.stream.IntStream.range(0,10).mapToObj(index->new LabRequests.Participant(agent.id,null)).toList();
        String id=text(service.create(new LabRequests.Experiment("Ten player review",null,10,10,"daily",true,1000,selection,Map.of(),participants),"admin").get("id"));
        service.control(id,"start");lab.advance(id);lab.advance(id);service.control(id,"pause");
        var match=matches.findByExperimentIdAndSequenceNumber(id,1).orElseThrow();finish(match,true);lab.advance(id);stubCoach();coach.process(id);lab.advance(id);
        assertEquals("PAUSED",experiments.findById(id).orElseThrow().status);return id;
    }
    @Test void perParticipantModelsFreezeWithoutChangingTheProfileAndRuleEliminationDoesNotAbortLearning() {
        var agent=service.saveAgent(null,new LabRequests.Agent("Same",personas.findAll().getFirst().getId(),selection));
        var alternate=new LabRequests.Selection("alternate-fixture",AiModelSelection.ThinkingMode.DEFAULT,null);
        var participants=java.util.stream.IntStream.range(0,4).mapToObj(index -> new LabRequests.Participant(agent.id,null,index==0?alternate:null)).toList();
        String id=text(service.create(new LabRequests.Experiment("Models",null,4,2,"daily",false,1000,selection,Map.of(),participants),"admin").get("id"));
        var roster=LabService.roster(experiments.findById(id).orElseThrow().configuration);
        assertEquals("alternate-fixture",map(roster.getFirst().get("modelSelection")).get("modelKey"));
        assertEquals("fixture-model",map(roster.get(1).get("modelSelection")).get("modelKey"));
        assertEquals("fixture-model",service.agents().stream().filter(profile -> agent.id.equals(profile.id)).findFirst().orElseThrow().modelSelection.get("modelKey"));
        service.control(id,"start");lab.advance(id);lab.advance(id);
        var match=matches.findByExperimentIdAndSequenceNumber(id,1).orElseThrow();
        var pending=jobs.findByRoomIdAndStatusIn(match.roomId,List.of("QUEUED")).getFirst();
        String word=text(map(pending.getObservation().get("privateFacts")).get("word"));
        for(int attempt=0;attempt<3;attempt++) {
            var running=turns.claim(pending.getId());
            game.complete(running.getId(),new AiTurnDecision(action("SPEAK",word,null),word,Map.of(),List.of(),Map.of(),Map.of("qualityFlags",List.of("SECRET_WORD_LEAK"))));
        }
        var detail=diagnostics.inspect(id,match.id);assertEquals("FORBIDDEN_WORD_ELIMINATED",map(detail.get("failure")).get("code"));
        assertThrows(com.aisocialgame.exception.ApiException.class,()->diagnostics.inspect("different",match.id));
        assertFalse(service.detail(id,null,false).containsKey("latestFailureMatchId"));
        finish(match);stubCoach();lab.advance(id);coach.process(id);lab.advance(id);
        assertEquals("REVIEWED",matches.findById(match.id).orElseThrow().status);
        assertEquals("RUNNING",experiments.findById(id).orElseThrow().status);
        assertEquals(4,experiences.findByExperimentIdOrderByCreatedAtDesc(id,org.springframework.data.domain.PageRequest.of(0,30)).getTotalElements());
    }

    @Test void serialExperimentsFreezeProfilesAndRecoverWithoutDuplicateGamesOrGlobalRewards() {
        String id=create(6,3);
        service.control(id,"start");
        lab.advance(id); lab.advance(id);
        var active=matches.findByExperimentIdAndStatusInOrderBySequenceNumberAsc(id,LabRuntime.ACTIVE);
        assertEquals(1,active.size());
        for(var match:active) {
            var state=states.findById(match.roomId).orElseThrow();
            assertTrue(state.getPlayers().stream().allMatch(GamePlayerState::isAi));
            var job=jobs.findByRoomIdAndStatusIn(match.roomId,List.of("QUEUED")).getFirst();
            assertEquals("fixture-model",map(job.getDiagnostics().get("modelSelection")).get("modelKey"));
            assertEquals("initial",map(map(job.getObservation().get("memory")).get("lab")).get("experienceId"));
        }
        lab.advance(id); assertEquals(1,matches.findByExperimentIdOrderBySequenceNumberDesc(id,org.springframework.data.domain.PageRequest.of(0,20)).getTotalElements());
        service.control(id,"pause");
        for(var match:active) finish(match);
        lab.advance(id);
        stubCoach();
        assertTrue(coach.process(id)); lab.advance(id);
        assertEquals("PAUSED",experiments.findById(id).orElseThrow().status);
        assertEquals(4,experiences.findByExperimentIdOrderByCreatedAtDesc(id,org.springframework.data.domain.PageRequest.of(0,30)).getTotalElements());
        assertFalse(coach.process(id));
        verify(client,times(1)).undercoverChatCompletions(anyString(),anyLong(),anyString(),any(AiModelSelection.class),anyList(),anyString(),anyInt());
        service.control(id,"resume"); lab.advance(id); lab.advance(id);
        var next=matches.findByExperimentIdAndStatusInOrderBySequenceNumberAsc(id,LabRuntime.ACTIVE).getFirst();
        var job=jobs.findByRoomIdAndStatusIn(next.roomId,List.of("QUEUED")).getFirst();
        var observation=json.convertValue(job.getObservation(),VisibleObservation.class);
        assertEquals("先核对多轮描述再判断，避免单凭风格投票。",map(AiPromptProjection.project(game.rules("undercover"),observation).get("labExperience")).get("summary"));
        assertTrue(rooms.findDiscoverable("undercover",null,java.time.LocalDateTime.now().minusHours(3),org.springframework.data.domain.PageRequest.of(0,100)).stream().noneMatch(Room::isLab));
    }
    @Test void fifoEnrollmentRequiresConfirmationAndAiFillsUnconfirmedSeats() {
        String id=create(1,1); service.control(id,"start");
        User first=user("human-first"),second=user("human-second"),third=user("human-third");
        service.enroll(id,first); service.enroll(id,second); service.enroll(id,third);
        var joinedAt=enrollments.findByExperimentIdAndUserId(id,first.getId()).orElseThrow().joinedAt;
        service.enroll(id,first); assertEquals(joinedAt,enrollments.findByExperimentIdAndUserId(id,first.getId()).orElseThrow().joinedAt);
        lab.advance(id);
        assertEquals("OFFERED",enrollments.findByExperimentIdAndUserId(id,first.getId()).orElseThrow().status);
        service.enrollmentAction(id,first,true);
        service.enrollmentAction(id,first,true);
        service.enrollmentAction(id,second,true);
        var match=matches.findByExperimentIdAndStatusInOrderBySequenceNumberAsc(id,LabRuntime.ACTIVE).getFirst();
        match.startsAtEpochMs=0; matches.saveAndFlush(match); lab.advance(id);
        var room=rooms.findById(match.roomId).orElseThrow();
        assertEquals(2,room.getSeats().stream().filter(seat -> !seat.isAi()).count());
        assertEquals(Set.of(first.getId(),second.getId()),new HashSet<>(room.getSeats().stream().filter(seat -> !seat.isAi()).map(RoomSeat::getPlayerId).toList()));
        var order=room.getSeats().stream().map(RoomSeat::getPlayerId).toList();
        assertEquals(order,matches.findById(match.id).orElseThrow().snapshot.get("seatOrder"));
        lab.advance(id); assertEquals(order,rooms.findById(match.roomId).orElseThrow().getSeats().stream().map(RoomSeat::getPlayerId).toList());
        assertEquals("CANCELLED",enrollments.findByExperimentIdAndUserId(id,third.getId()).orElseThrow().status);
        assertThrows(RuntimeException.class,()->service.enrollmentAction(id,first,false));
    }
    @Test void laboratoryRoomCodesRetryAnExistingHistoricalCode() {
        String first=create(1,1); service.control(first,"start"); lab.advance(first);
        var original=matches.findByExperimentIdAndStatusInOrderBySequenceNumberAsc(first,LabRuntime.ACTIVE).getFirst();
        String occupied=rooms.findById(original.roomId).orElseThrow().getRoomCode();
        doReturn(occupied).doCallRealMethod().when(codes).next();
        String second=create(1,1); service.control(second,"start"); lab.advance(second);
        var created=matches.findByExperimentIdAndStatusInOrderBySequenceNumberAsc(second,LabRuntime.ACTIVE).getFirst();
        assertNotEquals(occupied,rooms.findById(created.roomId).orElseThrow().getRoomCode());
    }
    @Test void unavailableFrozenModelPausesBeforeCreatingAnyFurtherGames() {
        String id=create(2,1); service.control(id,"start");
        doThrow(new com.aisocialgame.exception.ApiException(org.springframework.http.HttpStatus.CONFLICT,"fixture invalid model","MODEL_SELECTION_UNAVAILABLE",Map.of()))
                .when(models).requireUsable(any(AiModelSelection.class));
        lab.advance(id);
        var experiment=experiments.findById(id).orElseThrow();
        assertEquals("PAUSED",experiment.status); assertEquals("MODEL_SELECTION_UNAVAILABLE",experiment.reason);
        assertEquals(0,matches.findByExperimentIdOrderBySequenceNumberDesc(id,org.springframework.data.domain.PageRequest.of(0,1)).getTotalElements());
        verifyNoInteractions(client);
    }
    @Test void oneThousandPersistedGamesPublishOnceSeriallyAcrossRecreatedSchedulers() {
        String id=create(1000,3); service.control(id,"start"); stubCoach();
        var recoveredRuntime=new LabRuntime(experiments,matches,enrollments,evaluations,rooms,states,jobs,codes,game,models,budgets);
        var recoveredCoach=new LabCoachService(experiments,matches,experiences,states,client,properties,json,transactionManager,budgets);
        var transaction=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        int reviewed=0,largestBatch=0; var seatOrders=new HashSet<List<String>>();
        for(int cycle=0;cycle<2000 && !"COMPLETED".equals(experiments.findById(id).orElseThrow().status);cycle++) {
            var scheduler=recoveredRuntime;
            transaction.executeWithoutResult(status -> { scheduler.advance(id); scheduler.advance(id); });
            var active=matches.findByExperimentIdAndStatusInOrderBySequenceNumberAsc(id,LabRuntime.ACTIVE);
            largestBatch=Math.max(largestBatch,active.size()); assertTrue(active.size()<=1);
            for(var match:active) if("PLAYING".equals(match.status)) {
                seatOrders.add(strings(match.snapshot.get("seatOrder"))); finish(match,true);
            }
            transaction.executeWithoutResult(status -> scheduler.advance(id));
            for(int slot=0;slot<3;slot++) {
                if(recoveredCoach.process(id)) reviewed++;
                transaction.executeWithoutResult(status -> scheduler.advance(id));
            }
            if(cycle%50==0) {
                recoveredRuntime=new LabRuntime(experiments,matches,enrollments,evaluations,rooms,states,jobs,codes,game,models,budgets);
                recoveredCoach=new LabCoachService(experiments,matches,experiences,states,client,properties,json,transactionManager,budgets);
            }
        }
        assertEquals("COMPLETED",experiments.findById(id).orElseThrow().status);
        assertEquals(1000,reviewed); assertEquals(1,largestBatch);
        assertTrue(seatOrders.size()>1);
        var history=matches.findByExperimentIdOrderBySequenceNumberDesc(id,org.springframework.data.domain.PageRequest.of(0,1000)).getContent();
        assertEquals(1000,history.size()); assertEquals(1000,history.stream().map(match -> match.archiveId).distinct().count());
        assertTrue(history.stream().allMatch(match -> "REVIEWED".equals(match.status) && archives.existsById(match.archiveId)));
        assertEquals(4000,experiences.findByExperimentIdOrderByCreatedAtDesc(id,org.springframework.data.domain.PageRequest.of(0,1)).getTotalElements());
        assertEquals(1001,experiments.findById(id).orElseThrow().reviewCursor);
    }
    @Test void uncertainReviewResumesWithItsFrozenRequestIdentityAfterRecreation() {
        String id=create(1,1); service.control(id,"start"); lab.advance(id); lab.advance(id);
        finish(matches.findByExperimentIdAndStatusInOrderBySequenceNumberAsc(id,LabRuntime.ACTIVE).getFirst(),true); lab.advance(id);
        when(client.undercoverChatCompletions(anyString(),anyLong(),anyString(),any(AiModelSelection.class),anyList(),anyString(),anyInt()))
                .thenThrow(new com.aisocialgame.exception.ApiException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"fixture unknown","BUDGET_RECONCILIATION_REQUIRED",Map.of()));
        assertTrue(coach.process(id));
        var failed=matches.findByExperimentIdAndSequenceNumber(id,1).orElseThrow();
        String request=text(failed.snapshot.get("reviewRequestId")); var frozen=failed.snapshot.get("reviewRequest");
        stubCoach();
        var recovered=new LabCoachService(experiments,matches,experiences,states,client,properties,json,transactionManager,budgets);
        readyReview(failed.id); assertTrue(recovered.process(id)); lab.advance(id);
        var completed=matches.findById(failed.id).orElseThrow();
        assertEquals(request,completed.snapshot.get("reviewRequestId")); assertEquals(frozen,completed.snapshot.get("reviewRequest"));
        assertEquals("REVIEWED",completed.status); assertFalse(recovered.process(id));
        verify(client,times(2)).undercoverChatCompletions(anyString(),anyLong(),anyString(),any(AiModelSelection.class),anyList(),eq(request),anyInt());
    }
    @Test void reflectionFailureIsAtomicAndRequiresExplicitRetryAndRollbackCreatesANewVersion() {
        String id=create(2,1); service.control(id,"start"); lab.advance(id); lab.advance(id);
        var match=matches.findByExperimentIdAndStatusInOrderBySequenceNumberAsc(id,LabRuntime.ACTIVE).getFirst();
        finish(match); lab.advance(id);
        assertEquals(1,matches.findByExperimentIdOrderBySequenceNumberDesc(id,org.springframework.data.domain.PageRequest.of(0,20)).getTotalElements());
        when(client.undercoverChatCompletions(anyString(),anyLong(),anyString(),any(AiModelSelection.class),anyList(),anyString(),anyInt()))
                .thenReturn(new AiChatResult("{\"experiences\":[]}","fixture-model",1,1));
        coach.process(id); coach.process(id); coach.process(id);
        assertEquals(3,maps(matches.findById(match.id).orElseThrow().snapshot.get("reviewResponses")).size());
        assertEquals("PAUSED",experiments.findById(id).orElseThrow().status);
        assertEquals(0,experiences.findByExperimentIdOrderByCreatedAtDesc(id,org.springframework.data.domain.PageRequest.of(0,30)).getTotalElements());
        assertThrows(RuntimeException.class,()->service.control(id,"resume"));
        stubCoach(); coach.retry(id); service.control(id,"pause"); coach.process(id); lab.advance(id);
        var version=experiences.findByExperimentIdOrderByCreatedAtDesc(id,org.springframework.data.domain.PageRequest.of(0,30)).getContent().getFirst();
        service.rollback(id,version.id);
        assertEquals(2,experiences.latestSequence(id,version.participantId));
    }
    @Test void legacyRosterAndFrozenCoachRequestKeepTheirOriginalIdentities() throws Exception {
        String id=create(1,3); var experiment=experiments.findById(id).orElseThrow();
        var heads=new LinkedHashMap<String,Object>();
        var oldRoster=maps(experiment.configuration.get("roster"));
        var oldHeads=map(experiment.configuration.get("experiences"));
        for(var profile:oldRoster) { heads.put(text(profile.get("agentId")),oldHeads.get(LabService.participantId(profile))); profile.remove("participantId"); }
        experiment.configuration.put("roster",oldRoster); experiment.configuration.put("experiences",heads);
        experiment.configuration.put("initialExperiences",new LinkedHashMap<>(heads)); experiments.saveAndFlush(experiment);
        service.control(id,"start"); lab.advance(id); lab.advance(id);
        var match=matches.findByExperimentIdAndSequenceNumber(id,1).orElseThrow(); finish(match,true); lab.advance(id);
        when(client.undercoverChatCompletions(anyString(),anyLong(),anyString(),any(AiModelSelection.class),anyList(),anyString(),anyInt()))
                .thenThrow(new com.aisocialgame.exception.ApiException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"fixture unknown","BUDGET_RECONCILIATION_REQUIRED",Map.of()));
        coach.process(id); match=matches.findById(match.id).orElseThrow();
        var frozen=map(match.snapshot.get("reviewRequest")); frozen.remove("identityField");
        var messages=maps(frozen.get("messages"));
        var input=map(json.readValue(text(messages.getLast().get("content")),Map.class));
        var oldAgents=maps(input.get("agents")); oldAgents.forEach(profile -> profile.remove("participantId")); input.put("agents",oldAgents);
        messages.getLast().put("content",json.writeValueAsString(input)); frozen.put("messages",messages); match.snapshot.put("reviewRequest",frozen);
        matches.saveAndFlush(match); String requestId=text(match.snapshot.get("reviewRequestId"));
        stubCoach(); readyReview(match.id); coach.process(id); lab.advance(id);
        var completed=matches.findById(match.id).orElseThrow();
        assertEquals(frozen,completed.snapshot.get("reviewRequest")); assertEquals(requestId,completed.snapshot.get("reviewRequestId"));
        assertEquals("REVIEWED",completed.status);
        assertTrue(experiences.findByExperimentIdOrderByCreatedAtDesc(id,org.springframework.data.domain.PageRequest.of(0,30)).stream().allMatch(version -> version.agentId.equals(version.participantId)));
        assertFalse(map(service.detail(id,null,true).get("configuration")).containsKey("concurrency"));
    }
    @Test void pairedEvaluationFreezesOpponentsAndNeverPublishesExperience() {
        String id=create(1,2); service.control(id,"start"); lab.advance(id); lab.advance(id);
        finish(matches.findByExperimentIdAndStatusInOrderBySequenceNumberAsc(id,LabRuntime.ACTIVE).getFirst());
        lab.advance(id); stubCoach(); coach.process(id); lab.advance(id);
        var experience=experiences.findByExperimentIdOrderByCreatedAtDesc(id,org.springframework.data.domain.PageRequest.of(0,30)).getContent().getFirst();
        var evaluation=service.evaluate(id,new LabRequests.Evaluation(experience.participantId,null,1,experience.id));
        lab.advance(id); lab.advance(id);
        var first=matches.findByEvaluationIdOrderBySequenceNumberAsc(text(evaluation.get("id"))).getFirst();
        finish(first); lab.advance(id); lab.advance(id);
        var pair=matches.findByEvaluationIdOrderBySequenceNumberAsc(text(evaluation.get("id")));
        assertEquals(2,pair.size()); assertEquals(pair.getFirst().randomSeed,pair.getLast().randomSeed);
        var baseline=states.findById(pair.getFirst().roomId).orElseThrow();
        var candidate=states.findById(pair.getLast().roomId).orElseThrow();
        assertEquals(baseline.getData().get("wordPairId"),candidate.getData().get("wordPairId"));
        assertEquals(baseline.getPlayers().stream().map(GamePlayerState::getRole).toList(),candidate.getPlayers().stream().map(GamePlayerState::getRole).toList());
        for(var match:pair) finish(match);
        lab.advance(id);
        assertFalse(coach.process(id));
        assertEquals(4,experiences.findByExperimentIdOrderByCreatedAtDesc(id,org.springframework.data.domain.PageRequest.of(0,30)).getTotalElements());
        assertEquals(1,service.evaluationView(newEvaluation(text(evaluation.get("id")))).get("validPairs"));
        var stopped=service.evaluate(id,new LabRequests.Evaluation(experience.participantId,null,1,experience.id));
        lab.advance(id); service.control(id,"stop"); lab.advance(id);
        assertEquals("STOPPED",newEvaluation(text(stopped.get("id"))).status);
        assertEquals("STOPPED",experiments.findById(id).orElseThrow().status);
    }
    @Autowired LabEvaluationRepository evaluations;
    @Test void differentExperimentsUseTheGlobalResourceLimitWithoutOverlappingTheirOwnGames() {
        var ids=new ArrayList<String>();
        for(int index=0;index<4;index++) { String id=create(2,3); ids.add(id); service.control(id,"start"); lab.advance(id); lab.advance(id); }
        assertEquals(3,matches.countByStatusIn(LabRuntime.ACTIVE));
        for(String id:ids) assertTrue(matches.findByExperimentIdAndStatusInOrderBySequenceNumberAsc(id,LabRuntime.ACTIVE).size()<=1);
        assertTrue(matches.findByExperimentIdAndStatusInOrderBySequenceNumberAsc(ids.getLast(),LabRuntime.ACTIVE).isEmpty());
    }
    @Test void duplicateProfilesKeepIndependentExperiencesInheritanceAndPairedEvaluationIdentities() {
        String agent=service.saveAgent(null,new LabRequests.Agent("Duplicate",personas.findAll().getFirst().getId(),selection)).id;
        var participants=java.util.stream.IntStream.range(0,4).mapToObj(index -> new LabRequests.Participant(agent,null)).toList();
        String id=text(service.create(new LabRequests.Experiment("Copies",null,4,2,"daily",false,1000,selection,null,participants),"admin").get("id"));
        var roster=LabService.roster(experiments.findById(id).orElseThrow().configuration);
        assertEquals(4,roster.stream().map(LabService::participantId).distinct().count());
        assertEquals(List.of("Duplicate #1","Duplicate #2","Duplicate #3","Duplicate #4"),roster.stream().map(profile -> profile.get("name")).toList());
        service.control(id,"start"); lab.advance(id); lab.advance(id);
        var match=matches.findByExperimentIdAndSequenceNumber(id,1).orElseThrow();
        assertEquals(4,states.findById(match.roomId).orElseThrow().getPlayers().stream().map(GamePlayerState::getPlayerId).distinct().count());
        clearInvocations(connections); var observed=service.observe(id,match.id);
        verify(connections,never()).markActive(anyString(),anyString());
        var view=json.convertValue(observed.get("state"),Map.class);
        assertTrue(maps(view.get("players")).stream().allMatch(player -> !player.containsKey("role") || player.get("role")==null));
        assertThrows(RuntimeException.class,()->service.observe("another-experiment",match.id));
        service.control(id,"pause"); finish(match,true); lab.advance(id); stubCoach(); coach.process(id); lab.advance(id);
        var versions=experiences.findByExperimentIdOrderByCreatedAtDesc(id,org.springframework.data.domain.PageRequest.of(0,30)).getContent();
        assertEquals(4,versions.size()); assertEquals(4,versions.stream().map(version -> version.participantId).distinct().count());
        var chosen=versions.getFirst(); service.rollback(id,chosen.id);
        for(var version:versions) assertEquals(version==chosen?2:1,experiences.latestSequence(id,version.participantId));
        var inherited=versions.stream().map(version -> new LabRequests.Participant(agent,version.id)).toList();
        String child=text(service.create(new LabRequests.Experiment("Inherited copies",null,4,1,"daily",false,1000,selection,null,inherited),"admin").get("id"));
        var childConfig=experiments.findById(child).orElseThrow().configuration;
        var childRoster=LabService.roster(childConfig);
        for(int index=0;index<4;index++) {
            String participant=LabService.participantId(childRoster.get(index));
            assertFalse(roster.stream().anyMatch(profile -> participant.equals(LabService.participantId(profile))));
            assertEquals(versions.get(index).id,map(map(childConfig.get("experiences")).get(participant)).get("experienceId"));
        }
        assertThrows(RuntimeException.class,()->service.evaluate(id,new LabRequests.Evaluation(null,agent,1,chosen.id)));
        var evaluation=service.evaluate(id,new LabRequests.Evaluation(chosen.participantId,null,1,chosen.id));
        lab.advance(id); lab.advance(id);
        var baseline=matches.findByEvaluationIdOrderBySequenceNumberAsc(text(evaluation.get("id"))).getFirst();
        finish(baseline,true); lab.advance(id); lab.advance(id);
        var candidate=matches.findByEvaluationIdOrderBySequenceNumberAsc(text(evaluation.get("id"))).getLast();
        assertEquals(baseline.randomSeed,candidate.randomSeed);
        assertEquals(matches.findById(baseline.id).orElseThrow().snapshot.get("seatOrder"),candidate.snapshot.get("seatOrder"));
        var before=map(baseline.snapshot.get("players")); var after=map(candidate.snapshot.get("players"));
        assertEquals(1,before.keySet().stream().filter(actor -> !Objects.equals(map(before.get(actor)).get("experienceId"),map(after.get(actor)).get("experienceId"))).count());
        assertEquals(5,experiences.findByExperimentIdOrderByCreatedAtDesc(id,org.springframework.data.domain.PageRequest.of(0,1)).getTotalElements());
    }
    private LabEvaluation newEvaluation(String id) { return evaluations.findById(id).orElseThrow(); }
    @Test void evaluationIncludesAbortedAttemptsInFailureRatesWithoutCountingUnpairedWins() {
        String id=create(1,1);
        var evaluation=new LabEvaluation(); evaluation.experimentId=id; evaluation.agentId="fixture-agent"; evaluation.participantId="fixture-agent"; evaluation.pairs=2;
        evaluations.saveAndFlush(evaluation);
        evaluationMatch(evaluation,1,0,"BASELINE","REVIEWED",true,3,0);
        evaluationMatch(evaluation,2,0,"CANDIDATE","REVIEWED",false,5,0);
        evaluationMatch(evaluation,3,1,"BASELINE","ABORTED",false,1,1);
        evaluationMatch(evaluation,4,1,"CANDIDATE","REVIEWED",true,3,0);
        var report=service.evaluationView(evaluation);
        assertEquals(1,report.get("validPairs"));
        var baseline=map(report.get("baseline"));
        var candidate=map(report.get("candidate"));
        assertEquals(2,baseline.get("attemptedGames")); assertEquals(1L,baseline.get("failedGames"));
        assertEquals(0.5d,baseline.get("failureRate")); assertEquals(0d,candidate.get("failureRate"));
        assertEquals(0.8d,baseline.get("validActionRate"));
        assertEquals(1d,baseline.get("winRate")); assertEquals(0d,candidate.get("winRate"));
        assertEquals(1d,map(map(baseline.get("roles")).get("CIVILIAN")).get("winRate"));
    }
    private void evaluationMatch(LabEvaluation evaluation,int sequence,int pair,String variant,String status,boolean won,int succeeded,int failed) {
        var match=new LabMatch(); match.experimentId=evaluation.experimentId; match.evaluationId=evaluation.id;
        match.sequenceNumber=sequence; match.roomId=UUID.randomUUID().toString(); match.variant=variant; match.status=status;
        match.snapshot.put("pairIndex",pair);
        match.snapshot.put("results",List.of(Map.of("agentId",evaluation.agentId,"role","CIVILIAN","won",won,"successfulActions",succeeded,"failedActions",failed)));
        matches.saveAndFlush(match);
    }
    @Test void eightConcurrentQuotesDoNotConsumeTheExperimentBudgetAndSettleOnce() throws Exception {
        String id=create(1,1);
        var experiment=experiments.findById(id).orElseThrow(); experiment.status="RUNNING"; experiments.saveAndFlush(experiment);
        spent(id,8);
        long userId=Math.abs(UUID.randomUUID().getMostSignificantBits()%100_000_000)+2000;
        var account=new CreditAccount(); account.setUserId(userId); account.setProjectKey("aisocialgame"); account.setPermanentBalance(10000); accounts.saveAndFlush(account);
        var quote=BudgetPriceQuote.newBuilder().setCurrency("CNY").setPriceVersionId(1).setPriceSha256("a".repeat(64))
                .setPointsPerCurrency(100).setMaxCost("2.170000").setMaxCreditPoints(217).build();
        var requests=new ArrayList<ChatCompletionsRequest>();
        for(int index=0;index<8;index++) requests.add(ChatCompletionsRequest.newBuilder().setProjectKey("aisocialgame").setUserId(userId)
                .setRequestId(UUID.randomUUID().toString()).setModel("fixture-model").build());
        try(var workers=Executors.newFixedThreadPool(8)) {
            var attempts=requests.stream().map(request -> workers.submit(() -> {
                try(var context=LabCallContext.open(Map.of("experimentId",id,"matchId","fixture"),"TURN")) {
                    budgets.checkCall(); escrow.register(request,budget(request,quote)); return true;
                }
            })).toList();
            int acquired=0; for(var attempt:attempts) if(attempt.get(15,TimeUnit.SECONDS)) acquired++;
            assertEquals(8,acquired);
        }
        assertFalse(budgets.totals(id).containsKey("heldPoints"));
        assertEquals(8L,budgets.totals(id).get("pendingCalls"));
        assertEquals(10000,accounts.findByUserIdAndProjectKey(userId,"aisocialgame").orElseThrow().getPermanentBalance());
        assertEquals(8L,budgets.totals(id).get("spentPoints"));
        assertEquals("RUNNING",service.updateBudget(id,12).get("status"));
        var charge=BudgetCharge.newBuilder().setCurrency("CNY").setPriceVersionId(1).setPriceSha256("a".repeat(64)).setTotalCost("0.010000").setCreditPoints(1).build();
        for(var row:reservations.findByUserIdAndProjectKeyAndState(userId,"aisocialgame","PENDING")) {
            var receipt=GetBudgetCallStatusResponse.newBuilder().setBudgetId(row.budgetId).setRequestSha256(row.requestHash)
                .setState(BudgetCallState.BUDGET_CALL_STATE_SUCCEEDED).setUsageAuthoritative(true).setPromptTokens(10).setCompletionTokens(5).setTotalTokens(15)
                .setPriceQuote(quote).setCharge(charge).setChat(ChatCompletionsResponse.newBuilder().setModelKey("fixture-model").setContent("ok").setPromptTokens(10).setCompletionTokens(5).setBudgetCharge(charge)).build();
            escrow.reconcile(row.id,receipt); escrow.reconcile(row.id,receipt);
        }
        assertEquals(16L,budgets.totals(id).get("spentPoints")); assertEquals(0L,budgets.totals(id).get("pendingCalls"));
        assertEquals(9992,accounts.findByUserIdAndProjectKey(userId,"aisocialgame").orElseThrow().getPermanentBalance());
        try(var context=LabCallContext.open(Map.of("experimentId",id,"matchId","fixture"),"TURN")) {
            assertEquals(LabBudgetService.EXHAUSTED,assertThrows(com.aisocialgame.exception.ApiException.class,budgets::checkCall).getCode());
        }
        assertNull(LabCallContext.current());
    }
    private PrepareCallBudgetResponse budget(ChatCompletionsRequest request,BudgetPriceQuote quote) {
        return PrepareCallBudgetResponse.newBuilder().setBudgetId(UUID.randomUUID().toString()).setBudgetCredential("fixture")
                .setRequestSha256(AiCallSettlementService.hash(request.toByteArray())).setMaxPromptTokens(10).setMaxCompletionTokens(10).setMaxTotalTokens(20)
                .setMaxProviderAttempts(1).setExpiresAtEpochMs(System.currentTimeMillis()+60000).setPriceQuote(quote).build();
    }
    @Test void budgetPauseSurvivesReloadAndResumesTheSameTurnWithoutExpiringItsClock() {
        String id=create(3,1); var match=runningMatch(id);
        var state=states.findById(match.roomId).orElseThrow(); state.setPhaseEndsAt(java.time.LocalDateTime.now().plusSeconds(30)); states.saveAndFlush(state);
        String archive=text(state.getData().get("archiveId"));
        var job=turns.claim(jobs.findByRoomIdAndStatusIn(match.roomId,List.of("QUEUED")).getFirst().getId());
        spent(id,1000); game.defer(job.getId(),Map.of("nextAttempt",0),LabBudgetService.EXHAUSTED); lab.advance(id);
        assertEquals("PAUSED",experiments.findById(id).orElseThrow().status);
        assertEquals("PLAYING",matches.findById(match.id).orElseThrow().status);
        assertNull(states.findById(match.roomId).orElseThrow().getPhaseEndsAt()); assertNull(turns.claim(job.getId()));
        var persisted=jobs.findById(job.getId()).orElseThrow();
        assertFalse(persisted.getDiagnostics().containsKey("executionDeadlineEpochMs"));
        assertTrue(((Number)persisted.getDiagnostics().get("labBudgetRemainingMs")).longValue()>0);
        new org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult(status ->
                new LabRuntime(experiments,matches,enrollments,evaluations,rooms,states,jobs,codes,game,models,budgets).advance(id));
        game.tick(match.roomId);
        assertEquals(true,game.labSpectatorState(match.roomId).getExtra().get("labBudgetPaused"));
        assertEquals("PAUSED",service.updateBudget(id,2000).get("status")); assertNull(turns.claim(job.getId()));
        service.control(id,"resume");
        state=states.findById(match.roomId).orElseThrow();
        assertFalse(state.getData().containsKey("labBudgetClock")); assertTrue(state.getPhaseEndsAt().isAfter(java.time.LocalDateTime.now()));
        assertNotNull(turns.claim(job.getId()));
        game.complete(job.getId(),speech());
        assertEquals("SUCCEEDED",jobs.findById(job.getId()).orElseThrow().getStatus());
        assertEquals(archive,text(states.findById(match.roomId).orElseThrow().getData().get("archiveId")));
    }
    @Test void runningResultIsAppliedWhilePausedAndAdminCanEndThePreservedGame() {
        String id=create(3,1); var match=runningMatch(id);
        var job=turns.claim(jobs.findByRoomIdAndStatusIn(match.roomId,List.of("QUEUED")).getFirst().getId());
        spent(id,1001); lab.advance(id); game.complete(job.getId(),speech());
        assertEquals("SUCCEEDED",jobs.findById(job.getId()).orElseThrow().getStatus());
        assertEquals("PLAYING",matches.findById(match.id).orElseThrow().status);
        assertNull(states.findById(match.roomId).orElseThrow().getPhaseEndsAt());
        assertTrue(jobs.findByRoomIdAndStatusIn(match.roomId,List.of("QUEUED")).isEmpty());
        service.control(id,"stop"); lab.advance(id);
        assertEquals("STOPPED",experiments.findById(id).orElseThrow().status);
        var stopped=matches.findById(match.id).orElseThrow();
        assertEquals("ABORTED",stopped.status); assertEquals("LAB_STOPPED_BY_ADMIN",stopped.reason);
        assertTrue(archives.existsById(stopped.archiveId));
    }
    @Test void editingUsesActualDebitAsMinimumAndDoesNotAutomaticallyRestartAnyState() {
        String id=create(3,1); spent(id,12);
        for(String status:List.of("DRAFT","RUNNING","PAUSING","STOPPING","PAUSED","COMPLETED","STOPPED")) {
            var experiment=experiments.findById(id).orElseThrow(); experiment.status=status; experiment.configuration.remove("budgetPaused"); experiments.saveAndFlush(experiment);
            assertEquals(status,service.updateBudget(id,20).get("status"));
        }
        assertEquals("LAB_BUDGET_BELOW_SPENT",assertThrows(com.aisocialgame.exception.ApiException.class,()->service.updateBudget(id,11)).getCode());
        var experiment=experiments.findById(id).orElseThrow(); experiment.status="RUNNING"; experiments.saveAndFlush(experiment);
        assertEquals("PAUSED",service.updateBudget(id,12).get("status"));
        assertEquals("PAUSED",service.updateBudget(id,30).get("status"));
        assertThrows(com.aisocialgame.exception.ApiException.class,()->service.updateBudget(id,0));
    }
    @Test void budgetRequestRejectsFractionalStringAndOverflowValuesInsteadOfCoercingThem() throws Exception {
        assertEquals(1523,json.readValue("{\"budgetPoints\":1523}",LabRequests.Budget.class).budgetPoints());
        assertEquals(1523,readHttpBudget("{\"budgetPoints\":1523}").budgetPoints());
        for(String value:List.of("12.5","\"12\"","9223372036854775808","null")) {
            assertThrows(com.fasterxml.jackson.core.JsonProcessingException.class,()->json.readValue("{\"budgetPoints\":"+value+"}",LabRequests.Budget.class));
            assertThrows(org.springframework.http.converter.HttpMessageNotReadableException.class,()->readHttpBudget("{\"budgetPoints\":"+value+"}"));
        }
    }
    @SuppressWarnings("unchecked")
    private LabRequests.Budget readHttpBudget(String body) throws java.io.IOException {
        var converter=(org.springframework.http.converter.HttpMessageConverter<LabRequests.Budget>)httpAdapter.getMessageConverters().stream()
                .filter(value -> value.canRead(LabRequests.Budget.class,org.springframework.http.MediaType.APPLICATION_JSON)).findFirst().orElseThrow();
        var input=new org.springframework.mock.http.MockHttpInputMessage(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        input.getHeaders().setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        return converter.read(LabRequests.Budget.class,input);
    }
    @Test void budgetPausedCoachKeepsTheOriginalRequestForResume() {
        String id=create(1,1); var match=runningMatch(id); finish(match,true); lab.advance(id);
        when(client.undercoverChatCompletions(anyString(),anyLong(),anyString(),any(AiModelSelection.class),anyList(),anyString(),anyInt())).thenAnswer(invocation->{
            spent(id,1000); throw new com.aisocialgame.exception.ApiException(org.springframework.http.HttpStatus.CONFLICT,"budget",LabBudgetService.EXHAUSTED,Map.of());
        });
        assertTrue(coach.process(id)); var pending=matches.findById(match.id).orElseThrow();
        assertEquals("FINISHED",pending.status); assertNull(pending.reason);
        String original=text(pending.snapshot.get("reviewRequestId"));
        service.updateBudget(id,2000); service.control(id,"resume"); stubCoach();
        assertTrue(new LabCoachService(experiments,matches,experiences,states,client,properties,json,transactionManager,budgets).process(id));
        assertEquals("REVIEWED",matches.findById(match.id).orElseThrow().status);
        verify(client,times(2)).undercoverChatCompletions(anyString(),anyLong(),anyString(),any(AiModelSelection.class),anyList(),eq(original),anyInt());
    }
    @Test void raisingBudgetBeforeCoordinatorObservesAFrozenRoomStillRequiresExplicitResume() {
        String id=create(3,1); var match=runningMatch(id);
        game.pauseLabBudget(match.roomId);
        assertFalse(LabBudgetService.paused(experiments.findById(id).orElseThrow()));
        var updated=service.updateBudget(id,2000);
        assertEquals("PAUSED",updated.get("status")); assertEquals(true,updated.get("budgetPaused"));
        assertTrue(game.labBudgetPaused(match.roomId));
        service.control(id,"resume"); assertFalse(game.labBudgetPaused(match.roomId));
        assertEquals("PLAYING",matches.findById(match.id).orElseThrow().status);
    }
    @Test void allWorkCompletedAtTheBudgetDoesNotRequireAnotherTopUpToFinish() {
        String id=create(1,1); var match=runningMatch(id); finish(match,true); lab.advance(id);
        stubCoach(); assertTrue(coach.process(id)); spent(id,1000); lab.advance(id);
        assertEquals("COMPLETED",experiments.findById(id).orElseThrow().status);
        assertFalse(LabBudgetService.paused(experiments.findById(id).orElseThrow()));
    }
    @Test void tenVotesWithTwoThirtySecondQueueTimeoutsAndOneLostResponseRetainEverySuccessAndChargeOnce() throws Exception {
        var ids=new ArrayList<String>();
        for(int i=0;i<4;i++) ids.add(service.saveAgent(null,new LabRequests.Agent("base-"+i,personas.findAll().get(i).getId(),selection)).id);
        var participants=java.util.stream.IntStream.range(0,10).mapToObj(i->new LabRequests.Participant(ids.get(i%4),null,selection)).toList();
        String id=text(service.create(new LabRequests.Experiment("10 vote recovery",null,10,1,"daily",true,10000,selection,Map.of(),participants),"admin").get("id"));
        var match=runningMatch(id);
        for(int i=0;i<10;i++) {
            var pending=jobs.findByRoomIdAndStatusIn(match.roomId,List.of("QUEUED")).getFirst();
            game.complete(turns.claim(pending.getId()).getId(),speech());
        }
        var votes=jobs.findByRoomIdAndStatusIn(match.roomId,List.of("QUEUED"));assertEquals(10,votes.size());
        var claimed=votes.stream().map(job->turns.claim(job.getId())).toList();
        var unsent=Set.of(claimed.get(2).getId()+":0",claimed.get(3).getId()+":0");
        String lost=claimed.get(7).getId()+":0";
        var receipts=new HashMap<String,AiChatResult>();var transports=new ArrayList<String>();
        when(client.undercoverChatCompletions(anyString(),anyLong(),anyString(),any(AiModelSelection.class),anyList(),anyString(),anyInt())).thenAnswer(invocation->{
            String request=invocation.getArgument(5);transports.add(request);
            if(receipts.containsKey(request)) return receipts.get(request);
            if(unsent.contains(request)) throw new com.aisocialgame.exception.ApiException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                    "ACQUIRE_TIMEOUT","AI_REQUEST_NOT_SENT",Map.of("remoteState","FAILED_NOT_SENT","settlementState","RELEASED","providerConcurrency",1,"queueTimeoutMs",30000));
            String jobId=request.substring(0,request.indexOf(':'));
            var job=jobs.findById(jobId).orElseThrow();var payload=new LinkedHashMap<>(job.getObservation());payload.remove("generationCheckpoint");var observation=json.convertValue(payload,VisibleObservation.class);
            String target=observation.legalActions().stream().filter(legal->"VOTE".equals(legal.type())).findFirst().orElseThrow().targets().getFirst();
            var output=Map.of("action",Map.of("type","VOTE","targetPlayerId",target,"content",""),"speech","","presentation",Map.of(),"evidenceEventIds",List.of(),"memoryUpdates",Map.of());
            var response=new AiChatResult(json.writeValueAsString(output),"fixture-model",10,5);receipts.put(request,response);
            if(request.equals(lost)) throw new com.aisocialgame.exception.ApiException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"lost response","AI_RESULT_PENDING",Map.of("remoteState","RUNNING"));
            return response;
        });
        for(var job:claimed) turns.run(job);
        assertEquals(7,jobs.findByRoomIdAndStatusIn(match.roomId,List.of("SUCCEEDED")).stream().filter(job->"VOTE".equals(job.getKind())).count());
        assertEquals("PLAYING",matches.findById(match.id).orElseThrow().status);
        for(var queued:jobs.findByRoomIdAndStatusIn(match.roomId,List.of("QUEUED"))) {
            var checkpoint=map(queued.getObservation().get("generationCheckpoint"));checkpoint.put("retryAfterEpochMs",0);
            queued.getObservation().put("generationCheckpoint",checkpoint);jobs.saveAndFlush(queued);
            turns.run(turns.claim(queued.getId()));
        }
        for(var job:claimed) assertEquals("SUCCEEDED",jobs.findById(job.getId()).orElseThrow().getStatus());
        assertEquals(10,receipts.size(),"Exactly ten paid results, including the recovered lost response");
        assertEquals(2,transports.stream().filter(lost::equals).count());
        assertTrue(unsent.stream().allMatch(request->transports.contains(request+":transport:1")));
        assertFalse(states.findById(match.roomId).orElseThrow().getData().containsKey("labFailureReason"));
    }
    private void readyReview(String matchId) {
        var match=matches.findById(matchId).orElseThrow();
        var recovery=map(match.snapshot.get("reviewRecovery"));recovery.put("retryAfterEpochMs",0);
        match.snapshot.put("reviewRecovery",recovery);matches.saveAndFlush(match);
    }
    @Test void deletingProfilePreservesFrozenRosterAndAllExperienceVersions() {
        String id=create(1,1); var roster=LabService.roster(experiments.findById(id).orElseThrow().configuration);
        String agent=text(roster.getFirst().get("agentId")); service.deleteAgent(agent);
        assertTrue(service.agents().stream().noneMatch(row -> agent.equals(row.id)));
        var match=runningMatch(id);finish(match,true);lab.advance(id);stubCoach();coach.process(id);lab.advance(id);
        assertEquals("COMPLETED",experiments.findById(id).orElseThrow().status);
        assertEquals(4,experiences.findByExperimentIdOrderByCreatedAtDesc(id,org.springframework.data.domain.PageRequest.of(0,30)).getTotalElements());
        assertEquals(roster,LabService.roster(experiments.findById(id).orElseThrow().configuration));
    }
    @Test void callBlockPreservesSameGameAndQueuedActionAndAllowsAdministratorResume() {
        String id=create(1,1); var match=runningMatch(id);
        var pending=jobs.findByRoomIdAndStatusIn(match.roomId,List.of("QUEUED")).getFirst();var job=turns.claim(pending.getId());
        game.defer(job.getId(),Map.of("nextAttempt",0,"transportAttempt",4),"LAB_CALL_RETRIES_EXHAUSTED");
        try(var scope=LabCallContext.open(Map.of("experimentId",id,"matchId",match.id),"TURN")) {
            assertEquals("LAB_EXPERIMENT_PAUSED",assertThrows(com.aisocialgame.exception.ApiException.class,budgets::checkCall).getCode());
        }
        lab.advance(id);
        assertEquals("PAUSED",experiments.findById(id).orElseThrow().status);
        assertEquals("PLAYING",matches.findById(match.id).orElseThrow().status);
        assertEquals("QUEUED",jobs.findById(job.getId()).orElseThrow().getStatus());
        assertEquals(true,game.labSpectatorState(match.roomId).getExtra().get("labCallPaused"));
        service.control(id,"resume");assertNotNull(turns.claim(job.getId()));
        game.complete(job.getId(),speech());assertEquals("SUCCEEDED",jobs.findById(job.getId()).orElseThrow().getStatus());
    }
    @Test void expiredWallClockDoesNotConsumePureAiLabQueueOrDiscardItsLateResult() {
        String id=create(1,1);var match=runningMatch(id);
        var pending=jobs.findByRoomIdAndStatusIn(match.roomId,List.of("QUEUED")).getFirst();var job=turns.claim(pending.getId());
        var state=states.findById(match.roomId).orElseThrow();String phase=state.getPhase();
        state.setPhaseEndsAt(java.time.LocalDateTime.now().minusHours(1));states.saveAndFlush(state);
        assertTrue(game.remainingTurnMillis(job.getId())>0);game.tick(match.roomId);
        assertEquals(phase,states.findById(match.roomId).orElseThrow().getPhase());
        game.complete(job.getId(),speech());assertEquals("SUCCEEDED",jobs.findById(job.getId()).orElseThrow().getStatus());
        assertEquals("PLAYING",matches.findById(match.id).orElseThrow().status);
    }
    private LabMatch runningMatch(String id) {
        service.control(id,"start"); lab.advance(id);
        var match=matches.findByExperimentIdAndStatusInOrderBySequenceNumberAsc(id,List.of("WAITING")).getFirst();
        match.startsAtEpochMs=0; matches.saveAndFlush(match); lab.advance(id);
        return matches.findById(match.id).orElseThrow();
    }
    private AiTurnDecision speech() { return new AiTurnDecision(action("SPEAK","它有一种常见的用途。",null),"它有一种常见的用途。",Map.of(),List.of(),Map.of(),Map.of()); }
    private void spent(String id,long points) {
        var row=new com.aisocialgame.model.credit.AiCreditReservation(); row.id=UUID.randomUUID().toString();
        row.requestId="fixture-"+row.id; row.identityHash=AiCallSettlementService.hash(row.requestId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        row.requestHash=row.identityHash; row.projectKey="aisocialgame"; row.userId=85; row.budgetId=UUID.randomUUID().toString();
        row.state="SETTLED"; row.updatedAt=System.currentTimeMillis();
        row.labExperimentId=id; row.labPurpose="TURN"; row.billedPoints=points; row.billedCost=java.math.BigDecimal.valueOf(points,2);
        reservations.saveAndFlush(row);
    }
    private String create(int games,int concurrency) {
        var ids=new ArrayList<String>();
        for(int seat=0;seat<4;seat++) ids.add(service.saveAgent(null,new LabRequests.Agent("AI "+seat,personas.findAll().get(seat).getId(),selection)).id);
        String id=text(service.create(new LabRequests.Experiment("Lab fixture",ids,4,games,"daily",false,1000,selection,Map.of(),null),"admin").get("id"));
        var experiment=experiments.findById(id).orElseThrow(); experiment.configuration.put("concurrency",concurrency); experiments.saveAndFlush(experiment);
        return id;
    }
    private User user(String name) { var user=new User(); user.setId(UUID.randomUUID().toString()); user.setNickname(name); return user; }
    private void finish(LabMatch match) {
        finish(match,false);
    }
    private void finish(LabMatch match,boolean immediate) {
        for(int guard=0;guard<150;guard++) {
            var state=states.findById(match.roomId).orElseThrow();
            if("SETTLEMENT".equals(state.getPhase())) return;
            var pending=jobs.findByRoomIdAndStatusIn(match.roomId,List.of("QUEUED"));
            assertFalse(pending.isEmpty());
            var job=turns.claim(pending.getFirst().getId());
            var legal=game.rules("undercover").legalActions(state,job.getActorId());
            var speak=legal.stream().filter(option -> "SPEAK".equals(option.type())).findFirst();
            var action=immediate && legal.stream().anyMatch(option -> "SELF_DESTRUCT".equals(option.type()))
                    ?action("SELF_DESTRUCT",text(state.getData().get("civilianWord")),null)
                    :speak.isPresent()?action("SPEAK","它有一种常见的用途。",null):action("VOTE","",legal.stream().filter(option -> "VOTE".equals(option.type())).findFirst().orElseThrow().targets().getFirst());
            game.complete(job.getId(),new AiTurnDecision(action,action.getContent(),Map.of(),List.of(),Map.of(),Map.of()));
        }
        fail("game did not finish");
    }
    private void stubCoach() {
        when(client.undercoverChatCompletions(anyString(),anyLong(),anyString(),any(AiModelSelection.class),anyList(),anyString(),anyInt())).thenAnswer(invocation -> {
            assertNotNull(LabCallContext.current()); assertTrue(Set.of("REVIEW","MANUAL_REVIEW").contains(LabCallContext.current().purpose()));assertEquals(1200,(int)invocation.getArgument(6));
            List<AiChatMessageDto> messages=invocation.getArgument(4);
            var input=map(json.readValue(messages.get(1).content(),Map.class));
            String event=text(maps(input.get("events")).getFirst().get("eventId"));
            var entries=maps(input.get("agents")).stream().map(agent -> Map.of("agentId",agent.get("agentId"),"participantId",agent.getOrDefault("participantId",agent.get("agentId")),"summary","先核对多轮描述再判断，避免单凭风格投票。","evidenceEventIds",List.of(event))).toList();
            return new AiChatResult(json.writeValueAsString(Map.of("experiences",entries)),"fixture-model",1,1);
        });
    }
}
