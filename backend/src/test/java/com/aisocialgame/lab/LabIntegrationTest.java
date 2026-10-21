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
        User first=user("human-first"),second=user("human-second");
        service.enroll(id,first); service.enroll(id,second);
        var joinedAt=enrollments.findByExperimentIdAndUserId(id,first.getId()).orElseThrow().joinedAt;
        service.enroll(id,first); assertEquals(joinedAt,enrollments.findByExperimentIdAndUserId(id,first.getId()).orElseThrow().joinedAt);
        lab.advance(id);
        assertEquals("OFFERED",enrollments.findByExperimentIdAndUserId(id,first.getId()).orElseThrow().status);
        service.enrollmentAction(id,first,true);
        service.enrollmentAction(id,first,true);
        var match=matches.findByExperimentIdAndStatusInOrderBySequenceNumberAsc(id,LabRuntime.ACTIVE).getFirst();
        match.startsAtEpochMs=0; matches.saveAndFlush(match); lab.advance(id);
        var room=rooms.findById(match.roomId).orElseThrow();
        assertEquals(1,room.getSeats().stream().filter(seat -> !seat.isAi()).count());
        assertEquals(first.getId(),room.getSeats().getFirst().getPlayerId());
        assertEquals("CANCELLED",enrollments.findByExperimentIdAndUserId(id,second.getId()).orElseThrow().status);
        assertThrows(RuntimeException.class,()->service.enrollmentAction(id,first,false));
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
    }
    @Autowired LabEvaluationRepository evaluations;
    private LabEvaluation newEvaluation(String id) { return evaluations.findById(id).orElseThrow(); }
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
        for(int guard=0;guard<150;guard++) {
            var state=states.findById(match.roomId).orElseThrow();
            if("SETTLEMENT".equals(state.getPhase())) return;
            var pending=jobs.findByRoomIdAndStatusIn(match.roomId,List.of("QUEUED"));
            assertFalse(pending.isEmpty());
            var job=turns.claim(pending.getFirst().getId());
            var legal=game.rules("undercover").legalActions(state,job.getActorId());
            var speak=legal.stream().filter(option -> "SPEAK".equals(option.type())).findFirst();
            var action=speak.isPresent()?action("SPEAK","它有一种常见的用途。",null):action("VOTE","",legal.stream().filter(option -> "VOTE".equals(option.type())).findFirst().orElseThrow().targets().getFirst());
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
