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
import com.aisocialgame.service.credit.AiCreditEscrowService;
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
    @Autowired AiCreditEscrowService escrow;
    @Autowired CreditAccountRepository accounts;
    @Autowired AiCreditReservationRepository reservations;
    @Autowired ObjectMapper json;
    @Autowired com.aisocialgame.config.AppProperties properties;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean com.aisocialgame.service.RoomCodeGenerator codes;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired GameArchiveRepository archives;
    @MockitoBean AiModelConfigService models;
    @MockitoBean AiGrpcClient client;
    final LabRequests.Selection selection=new LabRequests.Selection("fixture-model",AiModelSelection.ThinkingMode.DEFAULT,null);

    @BeforeEach void stopPreviousFixtures() {
        for(var experiment:experiments.findAll()) { experiment.status="STOPPED"; experiments.save(experiment); }
        for(var match:matches.findAll()) { match.status="ABORTED"; matches.save(match); }
    }
    @Test void threePersistentTablesFreezeProfilesAndRecoverWithoutDuplicateGamesOrGlobalRewards() {
        String id=create(6,3);
        service.control(id,"start");
        lab.advance(id); lab.advance(id);
        var active=matches.findByExperimentIdAndStatusInOrderBySequenceNumberAsc(id,LabRuntime.ACTIVE);
        assertEquals(3,active.size());
        for(var match:active) {
            var state=states.findById(match.roomId).orElseThrow();
            assertTrue(state.getPlayers().stream().allMatch(GamePlayerState::isAi));
            var job=jobs.findByRoomIdAndStatusIn(match.roomId,List.of("QUEUED")).getFirst();
            assertEquals("fixture-model",map(job.getDiagnostics().get("modelSelection")).get("modelKey"));
            assertEquals("initial",map(map(job.getObservation().get("memory")).get("lab")).get("experienceId"));
        }
        lab.advance(id); assertEquals(3,matches.findByExperimentIdOrderBySequenceNumberDesc(id,org.springframework.data.domain.PageRequest.of(0,20)).getTotalElements());
        service.control(id,"pause");
        for(var match:active) finish(match);
        lab.advance(id);
        stubCoach();
        for(int review=0;review<3;review++) { assertTrue(coach.process(id)); lab.advance(id); }
        assertEquals("PAUSED",experiments.findById(id).orElseThrow().status);
        assertEquals(12,experiences.findByExperimentIdOrderByCreatedAtDesc(id,org.springframework.data.domain.PageRequest.of(0,30)).getTotalElements());
        assertFalse(coach.process(id));
        verify(client,times(3)).undercoverChatCompletions(anyString(),anyLong(),anyString(),any(AiModelSelection.class),anyList(),anyString(),anyInt());
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
        assertEquals(first.getId(),room.getSeats().getFirst().getPlayerId());
        assertEquals(second.getId(),room.getSeats().get(1).getPlayerId());
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
    @Test void oneThousandPersistedGamesPublishOnceAcrossThreeTablesAndRecreatedSchedulers() {
        String id=create(1000,3); service.control(id,"start"); stubCoach();
        var recoveredRuntime=new LabRuntime(experiments,matches,enrollments,evaluations,rooms,states,jobs,codes,game,models);
        var recoveredCoach=new LabCoachService(experiments,matches,experiences,states,client,properties,json,transactionManager);
        var transaction=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        int reviewed=0,largestBatch=0;
        for(int cycle=0;cycle<2000 && !"COMPLETED".equals(experiments.findById(id).orElseThrow().status);cycle++) {
            var scheduler=recoveredRuntime;
            transaction.executeWithoutResult(status -> { scheduler.advance(id); scheduler.advance(id); });
            var active=matches.findByExperimentIdAndStatusInOrderBySequenceNumberAsc(id,LabRuntime.ACTIVE);
            largestBatch=Math.max(largestBatch,active.size()); assertTrue(active.size()<=3);
            for(var match:active) if("PLAYING".equals(match.status)) finish(match,true);
            transaction.executeWithoutResult(status -> scheduler.advance(id));
            for(int slot=0;slot<3;slot++) {
                if(recoveredCoach.process(id)) reviewed++;
                transaction.executeWithoutResult(status -> scheduler.advance(id));
            }
            if(cycle%50==0) {
                recoveredRuntime=new LabRuntime(experiments,matches,enrollments,evaluations,rooms,states,jobs,codes,game,models);
                recoveredCoach=new LabCoachService(experiments,matches,experiences,states,client,properties,json,transactionManager);
            }
        }
        assertEquals("COMPLETED",experiments.findById(id).orElseThrow().status);
        assertEquals(1000,reviewed); assertEquals(3,largestBatch);
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
        var recovered=new LabCoachService(experiments,matches,experiences,states,client,properties,json,transactionManager);
        recovered.retry(id); assertTrue(recovered.process(id)); lab.advance(id);
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
        coach.process(id);
        assertEquals("PAUSED",experiments.findById(id).orElseThrow().status);
        assertEquals(0,experiences.findByExperimentIdOrderByCreatedAtDesc(id,org.springframework.data.domain.PageRequest.of(0,30)).getTotalElements());
        assertThrows(RuntimeException.class,()->service.control(id,"resume"));
        stubCoach(); coach.retry(id); service.control(id,"pause"); coach.process(id); lab.advance(id);
        var version=experiences.findByExperimentIdOrderByCreatedAtDesc(id,org.springframework.data.domain.PageRequest.of(0,30)).getContent().getFirst();
        service.rollback(id,version.id);
        assertEquals(2,experiences.latestSequence(id,version.agentId));
    }
    @Test void pairedEvaluationFreezesOpponentsAndNeverPublishesExperience() {
        String id=create(1,2); service.control(id,"start"); lab.advance(id); lab.advance(id);
        finish(matches.findByExperimentIdAndStatusInOrderBySequenceNumberAsc(id,LabRuntime.ACTIVE).getFirst());
        lab.advance(id); stubCoach(); coach.process(id); lab.advance(id);
        var experience=experiences.findByExperimentIdOrderByCreatedAtDesc(id,org.springframework.data.domain.PageRequest.of(0,30)).getContent().getFirst();
        var evaluation=service.evaluate(id,new LabRequests.Evaluation(experience.agentId,1,experience.id));
        lab.advance(id); lab.advance(id);
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
        var stopped=service.evaluate(id,new LabRequests.Evaluation(experience.agentId,1,experience.id));
        lab.advance(id); service.control(id,"stop"); lab.advance(id);
        assertEquals("STOPPED",newEvaluation(text(stopped.get("id"))).status);
        assertEquals("STOPPED",experiments.findById(id).orElseThrow().status);
    }
    @Autowired LabEvaluationRepository evaluations;
    private LabEvaluation newEvaluation(String id) { return evaluations.findById(id).orElseThrow(); }
    @Test void evaluationIncludesAbortedAttemptsInFailureRatesWithoutCountingUnpairedWins() {
        String id=create(1,1);
        var evaluation=new LabEvaluation(); evaluation.experimentId=id; evaluation.agentId="fixture-agent"; evaluation.pairs=2;
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
    @Test void concurrentBudgetReservationsIncludeUnknownHoldsAndSettlementReleasesOnlyTheDifference() throws Exception {
        String id=create(1,1);
        var experiment=experiments.findById(id).orElseThrow(); experiment.budgetPoints=150; experiment.status="RUNNING"; experiments.saveAndFlush(experiment);
        long userId=Math.abs(UUID.randomUUID().getMostSignificantBits()%100_000_000)+2000;
        var account=new CreditAccount(); account.setUserId(userId); account.setProjectKey("aisocialgame"); account.setPermanentBalance(10000); accounts.saveAndFlush(account);
        var quote=BudgetPriceQuote.newBuilder().setCurrency("CNY").setPriceVersionId(1).setPriceSha256("a".repeat(64))
                .setPointsPerCurrency(100).setMaxCost("1.000000").setMaxCreditPoints(100).build();
        var requests=new ArrayList<ChatCompletionsRequest>();
        for(int index=0;index<2;index++) requests.add(ChatCompletionsRequest.newBuilder().setProjectKey("aisocialgame").setUserId(userId)
                .setRequestId(UUID.randomUUID().toString()).setModel("fixture-model").build());
        try(var workers=Executors.newFixedThreadPool(2)) {
            var attempts=requests.stream().map(request -> workers.submit(() -> {
                try(var context=LabCallContext.open(Map.of("experimentId",id,"matchId","fixture"),"TURN")) {
                    try { escrow.reserve(request,budget(request,quote)); return true; }
                    catch(com.aisocialgame.exception.ApiException denied) { assertEquals("LAB_BUDGET_EXHAUSTED",denied.getCode()); return false; }
                }
            })).toList();
            int acquired=0; for(var attempt:attempts) if(attempt.get(15,TimeUnit.SECONDS)) acquired++;
            assertEquals(1,acquired);
        }
        assertEquals(100L,budgets.totals(id).get("heldPoints"));
        assertEquals(100L,budgets.totals(id).get("pendingPoints"));
        var row=reservations.findByUserIdAndProjectKeyAndState(userId,"aisocialgame","HELD").getFirst();
        var charge=BudgetCharge.newBuilder().setCurrency("CNY").setPriceVersionId(1).setPriceSha256("a".repeat(64)).setTotalCost("0.100000").setCreditPoints(10).build();
        escrow.reconcile(row.id,GetBudgetCallStatusResponse.newBuilder().setBudgetId(row.budgetId).setRequestSha256(row.requestHash)
                .setState(BudgetCallState.BUDGET_CALL_STATE_SUCCEEDED).setUsageAuthoritative(true).setPromptTokens(10).setCompletionTokens(5).setTotalTokens(15)
                .setPriceQuote(quote).setCharge(charge).setChat(ChatCompletionsResponse.newBuilder().setModelKey("fixture-model").setContent("ok").setPromptTokens(10).setCompletionTokens(5).setBudgetCharge(charge)).build());
        assertEquals(10L,budgets.totals(id).get("spentPoints")); assertEquals(0L,budgets.totals(id).get("heldPoints"));
        assertNull(LabCallContext.current());
    }
    private PrepareCallBudgetResponse budget(ChatCompletionsRequest request,BudgetPriceQuote quote) {
        return PrepareCallBudgetResponse.newBuilder().setBudgetId(UUID.randomUUID().toString()).setBudgetCredential("fixture")
                .setRequestSha256(AiCreditEscrowService.hash(request.toByteArray())).setMaxPromptTokens(10).setMaxCompletionTokens(10).setMaxTotalTokens(20)
                .setMaxProviderAttempts(1).setExpiresAtEpochMs(System.currentTimeMillis()+60000).setPriceQuote(quote).build();
    }
    private String create(int games,int concurrency) {
        var ids=new ArrayList<String>();
        for(int seat=0;seat<4;seat++) ids.add(service.saveAgent(null,new LabRequests.Agent("AI "+seat,personas.findAll().get(seat).getId(),selection)).id);
        return text(service.create(new LabRequests.Experiment("Lab fixture",ids,4,games,concurrency,"daily",false,1000,selection,Map.of()),"admin").get("id"));
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
            game.complete(job.getId(),new AiTurnDecision(action,action.getContent(),Map.of(),List.of(),Map.of(),false,Map.of()));
        }
        fail("game did not finish");
    }
    private void stubCoach() {
        when(client.undercoverChatCompletions(anyString(),anyLong(),anyString(),any(AiModelSelection.class),anyList(),anyString(),anyInt())).thenAnswer(invocation -> {
            assertNotNull(LabCallContext.current()); assertEquals("REVIEW",LabCallContext.current().purpose());
            List<AiChatMessageDto> messages=invocation.getArgument(4);
            var input=map(json.readValue(messages.getLast().content(),Map.class));
            String event=text(maps(input.get("events")).getFirst().get("eventId"));
            var entries=maps(input.get("agents")).stream().map(agent -> Map.of("agentId",agent.get("agentId"),"summary","先核对多轮描述再判断，避免单凭风格投票。","evidenceEventIds",List.of(event))).toList();
            return new AiChatResult(json.writeValueAsString(Map.of("experiences",entries)),"fixture-model",1,1);
        });
    }
}
