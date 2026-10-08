package com.aisocialgame;

import com.aisocialgame.dto.*;
import com.aisocialgame.engine.v2.*;
import com.aisocialgame.exception.ApiException;
import com.aisocialgame.integration.grpc.client.AiGrpcClient;
import com.aisocialgame.integration.grpc.dto.AiChatResult;
import com.aisocialgame.model.*;
import com.aisocialgame.repository.*;
import com.aisocialgame.service.*;
import com.aisocialgame.service.ai.v2.*;
import com.aisocialgame.service.v2.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(classes = AiSocialGameApplication.class)
@ActiveProfiles("test")
class V2GameServiceIntegrationTest {
    @Autowired V2GameService runtime;
    @Autowired GamePlayService facade;
    @Autowired RoomService roomService;
    @Autowired RoomRepository rooms;
    @Autowired GameStateRepository states;
    @Autowired AiTurnJobRepository jobs;
    @Autowired AiDecisionTraceRepository traces;
    @Autowired GameEventRepository events;
    @Autowired GameLogQueryService logQuery;
    @Autowired AiTurnCoordinator coordinator;
    @Autowired ReplayArchiveService replays;
    @Autowired AiPersonaMemoryRepository personaMemories;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired jakarta.persistence.EntityManagerFactory entityManagerFactory;
    @Autowired com.aisocialgame.service.safety.AiSafetyService safety;
    @MockitoBean AiGrpcClient client;
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @Test void pauseFreezesDeadlineAndLateResponseIsNotPublishedAndCanResume() {
        Room room=room(true);runtime.start("undercover",room.getId(),user(room.getHostUserId()));
        AiTurnJob queued=queued(room.getId());var running=coordinator.claim(queued.getId());
        GameState state=states.findById(room.getId()).orElseThrow(); state.setPhaseEndsAt(java.time.LocalDateTime.now().plusSeconds(40));states.saveAndFlush(state);
        var control=safety.createControl("ROOM",room.getId(),"PAUSE_ROOM","test",null,"admin");
        try {
            runtime.tick(room.getId());state=states.findById(room.getId()).orElseThrow();assertNull(state.getPhaseEndsAt());
            int eventCount=RuleSupport.maps(state.getData().get("events")).size();runtime.tick(room.getId());assertEquals(eventCount,RuleSupport.maps(states.findById(room.getId()).orElseThrow().getData().get("events")).size());
            runtime.complete(running.getId(),AiTurnDecision.fallback(RuleSupport.action("SPEAK","不应发布",null)));
            assertEquals("ADMIN_CONTROL",jobs.findById(running.getId()).orElseThrow().getDiagnostics().get("terminalReason"));
            assertFalse(states.findById(room.getId()).orElseThrow().getLogs().toString().contains("不应发布"));
        } finally {safety.disableControl(control.getId());}
        runtime.tick(room.getId());assertTrue(states.findById(room.getId()).orElseThrow().getPhaseEndsAt().isAfter(java.time.LocalDateTime.now().plusSeconds(30)));
        assertNotEquals(running.getId(),queued(room.getId()).getId());
    }

    @Test void manualObservationAllowsHumansAndFreezesTheNextOpportunityWithoutAiTakeover() {
        Room room=room(false); room.getConfig().put("speakTime",60); rooms.saveAndFlush(room);
        runtime.start("undercover",room.getId(),user(room.getHostUserId()));
        var control=safety.createControl("ROOM",room.getId(),"FORCE_OBSERVE","test",null,"admin");
        try {
            runtime.tick(room.getId());
            var before=states.findById(room.getId()).orElseThrow();
            var actor=before.getPlayers().stream().filter(p->p.getSeatNumber()==before.getCurrentSeat()).findFirst().orElseThrow();
            runtime.action("undercover",room.getId(),user(actor.getPlayerId()),RuleSupport.action("SPEAK","这是一次有依据的讨论。",null));
            var after=states.findById(room.getId()).orElseThrow();assertNull(after.getPhaseEndsAt());
            assertNotEquals(before.getCurrentSeat(),after.getCurrentSeat());
            assertTrue(jobs.findAll().stream().noneMatch(j->j.getRoomId().equals(room.getId())));
        } finally {safety.disableControl(control.getId());}
        runtime.tick(room.getId());assertNotNull(states.findById(room.getId()).orElseThrow().getPhaseEndsAt());
    }
    @Test void userMuteAlsoBlocksQuickReactionsAndPreservesAnAuditAfterRollback() {
        Room room=room(false);runtime.start("undercover",room.getId(),user(room.getHostUserId()));
        var control=safety.createControl("USER",room.getHostUserId(),"MUTE","test",null,"admin");
        try {assertThrows(ApiException.class,()->runtime.acceptSideChat(room.getId(),room.getHostUserId(),"EMOJI","👍"));}
        finally {safety.disableControl(control.getId());}
        assertTrue(runtime.acceptSideChat(room.getId(),room.getHostUserId(),"EMOJI","👍"));
    }

    @Test void getDoesNotProgressOrCreateJobsAndDuplicateHumanRequestCommitsOnce() throws Exception {
        Room room = room(false);
        runtime.start("undercover", room.getId(), user(room.getHostUserId()));
        GameState before = states.findById(room.getId()).orElseThrow();
        String snapshot = json.writeValueAsString(before);
        long jobCount = jobs.count();
        for (int i = 0; i < 4; i++) facade.state("undercover", room.getId(), user(room.getHostUserId()));
        assertEquals(snapshot, json.writeValueAsString(states.findById(room.getId()).orElseThrow()));
        assertEquals(jobCount, jobs.count());
        verifyNoInteractions(client);
        GamePlayerState speaker = before.getPlayers().stream().filter(p -> p.getSeatNumber() == before.getCurrentSeat()).findFirst().orElseThrow();
        GameStateResponse view = runtime.state("undercover", room.getId(), user(speaker.getPlayerId()));
        PlayerAction action = RuleSupport.action("SPEAK", "我会结合实际使用场景来说明，不急着下结论。", null);
        action.setRequestId(UUID.randomUUID().toString()); action.setExpectedPhaseToken(String.valueOf(view.getExtra().get("phaseToken")));
        runtime.action("undercover", room.getId(), user(speaker.getPlayerId()), action);
        long count = events.countByArchiveId(String.valueOf(before.getData().get("archiveId")));
        runtime.action("undercover", room.getId(), user(speaker.getPlayerId()), action);
        assertEquals(count, events.countByArchiveId(String.valueOf(before.getData().get("archiveId"))));
        action.setRequestId(UUID.randomUUID().toString());
        assertEquals("PHASE_CHANGED", assertThrows(ApiException.class, () -> runtime.action("undercover", room.getId(), user(speaker.getPlayerId()), action)).getCode());
    }

    @Test void oneWorkerClaimsJobAndModelRunsOutsideTransaction() throws Exception {
        Room room = room(true); runtime.start("undercover", room.getId(), user(room.getHostUserId()));
        AiTurnJob queued = queued(room.getId());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<AiTurnJob> claims;
        try {
            var a = pool.submit(() -> coordinator.claim(queued.getId()));
            var b = pool.submit(() -> coordinator.claim(queued.getId()));
            claims = Arrays.asList(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
        } finally { pool.shutdownNow(); }
        assertEquals(1, claims.stream().filter(Objects::nonNull).count());
        var recovery = jobs.findRecoveryCandidates("RUNNING", org.springframework.data.domain.PageRequest.of(0, 64)).stream().filter(c -> queued.getId().equals(c.getId())).findFirst().orElseThrow();
        assertNotNull(recovery.getDiagnostics().get("startedEpochMs"));
        assertFalse(recovery.getDiagnostics().containsKey("observation"));
        when(client.chatCompletions(anyString(), anyLong(), anyString(), anyString(), anyList(), anyString(), anyInt())).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "model request must release the transaction");
            return new AiChatResult("{\"action\":{\"type\":\"SPEAK\",\"content\":\"我先从实际使用场景来描述，再听听大家的说法。\"},\"speech\":\"我先从实际使用场景来描述，再听听大家的说法。\"}", "test-model", 30, 20);
        });
        coordinator.run(claims.stream().filter(Objects::nonNull).findFirst().orElseThrow());
        assertEquals("SUCCEEDED", jobs.findById(queued.getId()).orElseThrow().getStatus());
        assertEquals(1, traces.findAll().stream().filter(t -> queued.getId().equals(t.getQuality().get("jobId"))).count());
        var trace = traces.findAll().stream().filter(t -> queued.getId().equals(t.getQuality().get("jobId"))).findFirst().orElseThrow();
        assertEquals(1, trace.getQuality().get("presetVersion"));
        assertEquals(AiTurnGenerator.PROMPT_VERSION, trace.getQuality().get("promptVersion"));
        var diagnostic = jobs.findById(queued.getId()).orElseThrow().getDiagnostics();
        assertEquals("MODEL_ACTION_APPLIED", diagnostic.get("terminalReason"));
        assertTrue(diagnostic.containsKey("queueMs"));
        assertTrue(diagnostic.containsKey("observationMs"));
        assertTrue(diagnostic.containsKey("submissionProcessingMs"));
        assertEquals(diagnostic, trace.getQuality().get("diagnostics"));
        assertFalse(json.writeValueAsString(diagnostic).contains("我先从实际"));
        long count = events.countByArchiveId(queued.getInstanceId());
        VisibleObservation observation = json.convertValue(queued.getObservation(), VisibleObservation.class);
        runtime.complete(queued.getId(), AiTurnDecision.fallback(runtime.rules("undercover").fallback(observation)));
        assertEquals(diagnostic, jobs.findById(queued.getId()).orElseThrow().getDiagnostics());
        assertEquals(count, events.countByArchiveId(queued.getInstanceId()));
        assertEquals(1, traces.findAll().stream().filter(t -> queued.getId().equals(t.getQuality().get("jobId"))).count());
        verify(client, times(1)).chatCompletions(anyString(), anyLong(), anyString(), anyString(), anyList(), anyString(), anyInt());
    }

    @Test void rollbackRecoveryRecordsFailureWithoutSuccessTraceAndLateCompletionCannotRewriteIt() throws Exception {
        Room room = room(true); runtime.start("undercover", room.getId(), user(room.getHostUserId()));
        AiTurnJob job = coordinator.claim(queued(room.getId()).getId());
        String before = json.writeValueAsString(states.findById(room.getId()).orElseThrow().getData());
        var action = RuleSupport.action("SPEAK", "这次先看实际使用情境。", null);
        var decision = new AiTurnDecision(action, action.getContent(), Map.of(), List.of(),
                Map.of("hypotheses", List.of(Map.of("text", 42))), false, Map.of("latencyMs", 123L, "rawOutput", Map.of("private", "SECRET_RAW")));
        assertThrows(RuntimeException.class, () -> runtime.complete(job.getId(), decision));
        assertEquals(before, json.writeValueAsString(states.findById(room.getId()).orElseThrow().getData()));
        assertFalse(jobs.findById(job.getId()).orElseThrow().getDiagnostics().containsKey("terminalReason"));
        runtime.fail(job.getId(), "SUBMISSION_EXCEPTION", decision.diagnostics());
        var failed = jobs.findById(job.getId()).orElseThrow();
        assertEquals("FAILED", failed.getStatus()); assertEquals("SUBMISSION_EXCEPTION", failed.getDiagnostics().get("terminalReason"));
        String snapshot = json.writeValueAsString(failed.getDiagnostics()); assertFalse(snapshot.contains("SECRET_RAW"));
        assertTrue(traces.findAll().stream().noneMatch(t -> job.getId().equals(t.getQuality().get("jobId"))));
        runtime.complete(job.getId(), decision); runtime.fail(job.getId());
        assertEquals(snapshot, json.writeValueAsString(jobs.findById(job.getId()).orElseThrow().getDiagnostics()));
        verifyNoInteractions(client);
    }

    @Test void obsoleteGameResultCannotEnterRestartedGame() {
        Room room = room(true); runtime.start("undercover", room.getId(), user(room.getHostUserId()));
        AiTurnJob old = coordinator.claim(queued(room.getId()).getId());
        VisibleObservation observation = json.convertValue(old.getObservation(), VisibleObservation.class);
        GameState state = states.findById(room.getId()).orElseThrow();
        state.setPhase("SETTLEMENT"); states.saveAndFlush(state);
        runtime.start("undercover", room.getId(), user(room.getHostUserId()));
        String instance = String.valueOf(states.findById(room.getId()).orElseThrow().getData().get("archiveId"));
        assertNotEquals(old.getInstanceId(), instance);
        long count = events.countByArchiveId(instance);
        runtime.complete(old.getId(), AiTurnDecision.fallback(runtime.rules("undercover").fallback(observation)));
        assertEquals("DISCARDED", jobs.findById(old.getId()).orElseThrow().getStatus());
        assertEquals("INSTANCE_UNAVAILABLE", jobs.findById(old.getId()).orElseThrow().getDiagnostics().get("terminalReason"));
        assertEquals(count, events.countByArchiveId(instance));
    }

    @Test void structuredMemoryCommitsAtomicallyAndDuplicateJobCannotAppendItAgain() throws Exception {
        Room room = room(true); runtime.start("undercover", room.getId(), user(room.getHostUserId()));
        AiTurnJob job = coordinator.claim(queued(room.getId()).getId());
        VisibleObservation observation = json.convertValue(job.getObservation(), VisibleObservation.class);
        GameState state = states.findById(room.getId()).orElseThrow();
        state.getData().put("aiMemoriesV2", Map.of(job.getActorId(), Map.of("commitments", List.of("旧承诺保持待核对"))));
        states.saveAndFlush(state);
        String before = json.writeValueAsString(states.findById(room.getId()).orElseThrow().getData());
        long eventCount = events.countByArchiveId(job.getInstanceId());
        String target = state.getPlayers().stream().filter(p -> !p.getPlayerId().equals(job.getActorId())).findFirst().orElseThrow().getPlayerId();
        String quote = "我本轮投给" + RuleSupport.player(state, target).getSeatNumber() + "号";
        PlayerAction action = RuleSupport.action("SPEAK", quote, null);
        var decision = new AiTurnDecision(action, quote, Map.of(), List.of(),
                Map.of("hypotheses", List.of(Map.of("text", "尚待后续线索检验", "confidence", .4)),
                        "commitments", List.of(Map.of("text", quote, "sourceQuote", quote,
                                "action", Map.of("kind", "VOTE", "targetPlayerId", target), "roundOffset", 0))), false, Map.of());
        new org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            runtime.complete(job.getId(), decision);
            assertEquals("SUCCEEDED", jobs.findById(job.getId()).orElseThrow().getStatus());
            status.setRollbackOnly();
        });
        assertEquals(before, json.writeValueAsString(states.findById(room.getId()).orElseThrow().getData()));
        assertEquals("RUNNING", jobs.findById(job.getId()).orElseThrow().getStatus());
        assertFalse(jobs.findById(job.getId()).orElseThrow().getDiagnostics().containsKey("terminalReason"));
        assertEquals(eventCount, events.countByArchiveId(job.getInstanceId()));
        assertEquals(0, traces.findAll().stream().filter(t -> job.getId().equals(t.getQuality().get("jobId"))).count());

        runtime.complete(job.getId(), decision);
        Map<String, Object> committed = RuleSupport.map(RuleSupport.map(states.findById(room.getId()).orElseThrow().getData().get("aiMemoriesV2")).get(job.getActorId()));
        assertEquals(AiMemoryEntries.FORMAT_VERSION, committed.get("formatVersion"));
        assertEquals("MODEL_PROPOSAL", RuleSupport.maps(committed.get("hypotheses")).getFirst().get("source"));
        assertEquals("LEGACY_UNVERIFIED", RuleSupport.maps(committed.get("commitments")).getFirst().get("source"));
        assertEquals("UNVERIFIED", RuleSupport.maps(committed.get("commitments")).getFirst().get("status"));
        assertEquals("ACTIVE", RuleSupport.maps(committed.get("commitments")).getLast().get("status"));
        assertNotNull(RuleSupport.maps(committed.get("commitments")).getLast().get("sourceEventId"));
        assertEquals(1, RuleSupport.maps(committed.get("recentDecisions")).size());
        runtime.complete(job.getId(), decision);
        assertEquals(committed, RuleSupport.map(RuleSupport.map(states.findById(room.getId()).orElseThrow().getData().get("aiMemoriesV2")).get(job.getActorId())));
        assertEquals(1, traces.findAll().stream().filter(t -> job.getId().equals(t.getQuality().get("jobId"))).count());
        verifyNoInteractions(client);
    }

    @Test void expiredChallengeWithoutATickRecoversWithoutCallingTheModelOrStrandingTheJob() {
        Room room = room(true); runtime.start("undercover", room.getId(), user(room.getHostUserId()));
        for (int i = 0; i < 4; i++) {
            AiTurnJob description = coordinator.claim(queued(room.getId()).getId());
            VisibleObservation observation = json.convertValue(description.getObservation(), VisibleObservation.class);
            runtime.complete(description.getId(), AiTurnDecision.fallback(runtime.rules("undercover").fallback(observation)));
        }
        AiTurnJob challenge = coordinator.claim(queued(room.getId()).getId());
        GameState state = states.findById(room.getId()).orElseThrow();
        assertEquals("CHALLENGE", state.getPhase());
        state.setPhaseEndsAt(java.time.LocalDateTime.now().minusSeconds(1)); states.saveAndFlush(state);

        assertEquals(0, runtime.remainingTurnMillis(challenge.getId()));
        coordinator.run(challenge);

        verifyNoInteractions(client);
        assertEquals("FAILED", jobs.findById(challenge.getId()).orElseThrow().getStatus());
        assertEquals("NO_ACTION_BUDGET", jobs.findById(challenge.getId()).orElseThrow().getDiagnostics().get("terminalReason"));
        GameState next = states.findById(room.getId()).orElseThrow();
        assertTrue(runtime.rules("undercover").pendingTurns(next).stream().noneMatch(turn -> turn.key().equals(challenge.getTurnKey())));
        assertTrue(jobs.findAll().stream().anyMatch(job -> room.getId().equals(job.getRoomId()) && "QUEUED".equals(job.getStatus())));
    }

    @Test void aLateResultIsDiscardedWhenTheActionDeadlinePassesBeforeCommit() {
        Room room = room(true); runtime.start("undercover", room.getId(), user(room.getHostUserId()));
        AiTurnJob job = coordinator.claim(queued(room.getId()).getId());
        VisibleObservation observation = json.convertValue(job.getObservation(), VisibleObservation.class);
        AiTurnDecision result = AiTurnDecision.fallback(runtime.rules("undercover").fallback(observation));
        GameState state = states.findById(room.getId()).orElseThrow();
        state.setPhaseEndsAt(java.time.LocalDateTime.now().minusSeconds(1)); states.saveAndFlush(state);

        runtime.complete(job.getId(), result);

        assertEquals("DISCARDED", jobs.findById(job.getId()).orElseThrow().getStatus());
        assertEquals("TURN_EXPIRED", jobs.findById(job.getId()).orElseThrow().getDiagnostics().get("terminalReason"));
        GameState next = states.findById(room.getId()).orElseThrow();
        assertTrue(next.getLogs().stream().noneMatch(log -> "SPEAK".equals(log.getType())), "late dialogue must not be published");
        assertTrue(RuleSupport.map(next.getData().get("aiMemoriesV2")).isEmpty(), "late memory updates must not be committed");
        verifyNoInteractions(client);
    }

    @Test void customAuthorHasAuthorityWithoutSeatAndCannotSeeParticipantSecrets() throws Exception {
        User author = user(UUID.randomUUID().toString());
        Room room = roomService.createRoom("undercover", "自定义主持测试", false, null, "text", Map.of("playerCount", 4, "wordPack", "custom", "customWords", List.of(Map.of("wordA", "青苹果", "wordB", "红苹果"))), author);
        assertEquals(author.getId(), room.getHostUserId()); assertTrue(room.getSeats().isEmpty());
        assertFalse(json.writeValueAsString(new RoomResponse(room)).contains("青苹果"));
        assertThrows(ApiException.class, () -> roomService.joinRoom(room.getId(), "作者", author, null));
        List<RoomSeat> seats = new ArrayList<>();
        for (int i = 0; i < 4; i++) seats.add(new RoomSeat(i, UUID.randomUUID().toString(), "玩家" + i, false, null, "", true, false));
        room.setSeats(seats); room.syncSeatCount(); rooms.saveAndFlush(room);
        GameStateResponse view = runtime.start("undercover", room.getId(), author);
        assertNull(view.getMyWord()); assertNull(view.getMyRole());
        assertTrue(view.getPlayers().stream().allMatch(p -> p.getRole() == null && p.getWord() == null));
        assertTrue(((List<?>) view.getExtra().get("legalActions")).isEmpty());
        assertThrows(ApiException.class, () -> runtime.action("undercover", room.getId(), author, RuleSupport.action("SPEAK", "我出过题", null)));
        assertThrows(ApiException.class, () -> runtime.start("undercover", room.getId(), user(seats.get(0).getPlayerId())));
    }

    @Test void v2ReplayRejectsForgedPerspectiveAndGodMode() {
        Room room = room(false); runtime.start("undercover", room.getId(), user(room.getHostUserId()));
        GameState state = states.findById(room.getId()).orElseThrow();
        String archive = String.valueOf(state.getData().get("archiveId"));
        assertThrows(ApiException.class, () -> replays.archiveFinishedGame(state, room));
        state.setPhase("SETTLEMENT");
        replays.archiveFinishedGame(state, room);
        String first = room.getSeats().get(0).getPlayerId(); String second = room.getSeats().get(1).getPlayerId();
        assertThrows(ApiException.class, () -> replays.authorizedEvents(archive, "GOD", null, null));
        assertThrows(ApiException.class, () -> replays.authorizedEvents(archive, "PUBLIC", null, null));
        var publicReplay = replays.authorizedEvents(archive, "PUBLIC", null, "logged-spectator");
        assertTrue(publicReplay.getEvents().stream().allMatch(e -> e.getVisibility() == GameEventVisibility.PUBLIC));
        assertThrows(ApiException.class, () -> replays.authorizedEvents(archive, "PLAYER", second, first));
        var ownReplay = replays.authorizedEvents(archive, "PLAYER", first, first);
        assertEquals(1, ownReplay.getEvents().stream().filter(e -> e.getEventType().equals("WORD_ASSIGNED")).count());
        assertTrue(ownReplay.getEvents().stream().filter(e -> e.getEventType().equals("WORD_ASSIGNED")).allMatch(e -> first.equals(e.getActorPlayerId())));
    }

    @Test void simultaneousFacadeVotesReadStateAfterAcquiringTheRoomLock() throws Exception {
        Room room = room(false); runtime.start("undercover", room.getId(), user(room.getHostUserId()));
        for (int step = 0; step < 12; step++) {
            GameState state = states.findById(room.getId()).orElseThrow();
            if ("VOTING".equals(state.getPhase())) break;
            GamePlayerState actor = state.getPlayers().stream().filter(p -> !runtime.rules("undercover").legalActions(state, p.getPlayerId()).isEmpty()).findFirst().orElseThrow();
            boolean skip = runtime.rules("undercover").legalActions(state, actor.getPlayerId()).stream().anyMatch(a -> a.type().equals("SKIP"));
            runtime.action("undercover", room.getId(), user(actor.getPlayerId()), RuleSupport.action(skip ? "SKIP" : "SPEAK", skip ? "" : "我先描述一种常见的使用场景。", null));
        }
        assertEquals("VOTING", states.findById(room.getId()).orElseThrow().getPhase());
        String target = room.getSeats().get(2).getPlayerId();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<GameStateResponse>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                String actor = room.getSeats().get(i).getPlayerId();
                results.add(pool.submit(() -> facade.action("undercover", room.getId(), RuleSupport.action("VOTE", "", target), user(actor))));
            }
            for (var result : results) assertNotNull(result.get(10, TimeUnit.SECONDS));
        } finally { pool.shutdownNow(); }
        assertEquals(2, RuleSupport.map(states.findById(room.getId()).orElseThrow().getData().get("votes")).size());
    }

    @Test void concurrentFirstPersonaSettlementsAccumulateAtomically() throws Exception {
        String persona = "concurrent-" + UUID.randomUUID();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> new org.springframework.transaction.support.TransactionTemplate(transactionManager).execute(status -> { personaMemories.incrementGamesPlayed(persona, "undercover"); return true; }));
            var second = pool.submit(() -> new org.springframework.transaction.support.TransactionTemplate(transactionManager).execute(status -> { personaMemories.incrementGamesPlayed(persona, "undercover"); return true; }));
            assertTrue(first.get(10, TimeUnit.SECONDS)); assertTrue(second.get(10, TimeUnit.SECONDS));
        } finally { pool.shutdownNow(); }
        var memory = personaMemories.findByPersonaIdAndGameIdAndRoleKey(persona, "undercover", "GENERAL_V2").orElseThrow();
        assertEquals(2, memory.getGamesPlayed()); assertEquals("PENDING", memory.getReviewStatus()); assertNull(memory.getApprovedSummary());
    }

    @Test void osivRoutingReadDoesNotHideAnotherVotersCommittedBallot() throws Exception {
        Room room = room(false); runtime.start("undercover", room.getId(), user(room.getHostUserId()));
        for (int step = 0; step < 12; step++) {
            GameState state = states.findById(room.getId()).orElseThrow();
            if ("VOTING".equals(state.getPhase())) break;
            GamePlayerState actor = state.getPlayers().stream().filter(p -> !runtime.rules("undercover").legalActions(state, p.getPlayerId()).isEmpty()).findFirst().orElseThrow();
            boolean skip = runtime.rules("undercover").legalActions(state, actor.getPlayerId()).stream().anyMatch(a -> a.type().equals("SKIP"));
            runtime.action("undercover", room.getId(), user(actor.getPlayerId()), RuleSupport.action(skip ? "SKIP" : "SPEAK", skip ? "" : "我先描述一种常见的使用场景。", null));
        }
        assertEquals("VOTING", states.findById(room.getId()).orElseThrow().getPhase());
        String first = room.getSeats().get(0).getPlayerId();
        String second = room.getSeats().get(1).getPlayerId();
        String target = room.getSeats().get(2).getPlayerId();
        assertFalse(TransactionSynchronizationManager.hasResource(entityManagerFactory));
        var osiv = entityManagerFactory.createEntityManager();
        ExecutorService otherRequest = Executors.newSingleThreadExecutor();
        try {
            TransactionSynchronizationManager.bindResource(entityManagerFactory, new org.springframework.orm.jpa.EntityManagerHolder(osiv));
            // Reproduce the facade's preliminary route check in a web request's
            // still-open persistence context, before the mutation lock is held.
            assertTrue(runtime.isV2(room.getId()));
            assertEquals(0, osiv.unwrap(org.hibernate.Session.class).getStatistics().getEntityCount(),
                    "The routing read must not install an entity before the action acquires its lock");
            assertNotNull(otherRequest.submit(() -> facade.action("undercover", room.getId(),
                    RuleSupport.action("VOTE", "", target), user(first))).get(10, TimeUnit.SECONDS));
            var response = facade.action("undercover", room.getId(), RuleSupport.action("VOTE", "", target), user(second));
            assertEquals(Set.of(first, second), new HashSet<>(RuleSupport.strings(response.getExtra().get("votedPlayers"))));
        } finally {
            TransactionSynchronizationManager.unbindResource(entityManagerFactory);
            osiv.close(); otherRequest.shutdownNow();
        }
        assertEquals(Map.of(first, target, second, target), RuleSupport.map(states.findById(room.getId()).orElseThrow().getData().get("votes")));
    }

    @Test void reactionTypeCannotSmuggleFreeTextOrSecretWordsIntoPublicEvents() {
        Room room = room(false); runtime.start("undercover", room.getId(), user(room.getHostUserId()));
        String actor = room.getSeats().get(0).getPlayerId();
        assertFalse(runtime.acceptSideChat(room.getId(), actor, "EMOJI", "任意自由发言"));
        assertFalse(runtime.acceptSideChat(room.getId(), actor, "QUICK_PHRASE", "我的秘密词语"));
        assertTrue(runtime.acceptSideChat(room.getId(), actor, "EMOJI", "👍"));
        assertTrue(runtime.acceptSideChat(room.getId(), actor, "QUICK_PHRASE", "我同意"));
    }


    @Test void restoredHumanVoteResolvesAiPromiseAtomicallyAndDuplicateRequestIsPure() throws Exception {
        Room room = room(true); runtime.start("undercover", room.getId(), user(room.getHostUserId()));
        AiTurnJob job = coordinator.claim(queued(room.getId()).getId());
        GameState state = states.findById(room.getId()).orElseThrow();
        String actor = job.getActorId();
        GamePlayerState player = RuleSupport.player(state, actor); player.setAi(false); player.setConnectionStatus("AI_TAKEOVER");
        String target = state.getPlayers().stream().filter(p -> !p.getPlayerId().equals(actor)).findFirst().orElseThrow().getPlayerId();
        states.saveAndFlush(state);
        String quote = "我本轮投给" + RuleSupport.player(state, target).getSeatNumber() + "号";
        runtime.complete(job.getId(), new AiTurnDecision(RuleSupport.action("SPEAK", quote, null), quote, Map.of(), List.of(),
                Map.of("commitments", List.of(Map.of("text", quote, "sourceQuote", quote, "action", Map.of("kind", "VOTE", "targetPlayerId", target)))), false, Map.of()));
        assertEquals("ACTIVE", commitment(room.getId(), actor).get("status"));
        state = states.findById(room.getId()).orElseThrow(); state.setPhase("VOTING"); state.setCurrentSeat(null); state.setPhaseEndsAt(java.time.LocalDateTime.now().plusMinutes(1));
        state.getData().put("votes", Map.of()); states.saveAndFlush(state);
        String before = json.writeValueAsString(states.findById(room.getId()).orElseThrow().getData());
        PlayerAction vote = RuleSupport.action("SKIP", "", null); vote.setRequestId(UUID.randomUUID().toString());
        new org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            runtime.action("undercover", room.getId(), user(actor), vote);
            assertEquals("NOT_FULFILLED", commitment(room.getId(), actor).get("status")); tx.setRollbackOnly();
        });
        assertEquals(before, json.writeValueAsString(states.findById(room.getId()).orElseThrow().getData()));
        runtime.action("undercover", room.getId(), user(actor), vote);
        assertEquals("NOT_FULFILLED", commitment(room.getId(), actor).get("status"));
        assertEquals("HUMAN", RuleSupport.map(commitment(room.getId(), actor).get("resolution")).get("origin"));
        String after = json.writeValueAsString(states.findById(room.getId()).orElseThrow().getData());
        runtime.action("undercover", room.getId(), user(actor), vote);
        for (int i = 0; i < 3; i++) runtime.state("undercover", room.getId(), user(actor));
        assertEquals(after, json.writeValueAsString(states.findById(room.getId()).orElseThrow().getData()));
        assertFalse(runtime.state("undercover", room.getId(), user(target)).getExtra().toString().contains("NOT_FULFILLED"));
        verifyNoInteractions(client);
    }

    @Test void expiredVoteIsRecordedAsLossOfOpportunityRatherThanFulfilledAbstention() {
        Room room = room(true); runtime.start("undercover", room.getId(), user(room.getHostUserId()));
        AiTurnJob job = coordinator.claim(queued(room.getId()).getId());
        String quote = "我本轮弃票";
        runtime.complete(job.getId(), new AiTurnDecision(RuleSupport.action("SPEAK", quote, null), quote, Map.of(), List.of(),
                Map.of("commitments", List.of(Map.of("text", quote, "sourceQuote", quote, "action", Map.of("kind", "ABSTAIN")))), false, Map.of()));
        assertEquals("ACTIVE", commitment(room.getId(), job.getActorId()).get("status"));
        GameState state = states.findById(room.getId()).orElseThrow(); state.setPhase("VOTING"); state.setCurrentSeat(null);
        state.getData().put("votes", Map.of()); state.setPhaseEndsAt(java.time.LocalDateTime.now().minusSeconds(1)); states.saveAndFlush(state);
        runtime.tick(room.getId());
        assertEquals("EXPIRED", commitment(room.getId(), job.getActorId()).get("status"));
        assertEquals("TIMEOUT", RuleSupport.map(commitment(room.getId(), job.getActorId()).get("resolution")).get("origin"));
        verifyNoInteractions(client);
    }
    private Map<String, Object> commitment(String roomId, String actor) {
        return RuleSupport.maps(RuleSupport.map(RuleSupport.map(states.findById(roomId).orElseThrow().getData().get("aiMemoriesV2")).get(actor)).get("commitments")).getLast();
    }

    @Test void stateReturnsOnlyLatestOneHundredPublicLogsAndStableViewVersion() {
        Room room = room(false);
        runtime.start("undercover", room.getId(), user(room.getHostUserId()));
        GameState current = states.findById(room.getId()).orElseThrow();
        for (int i = 0; i < 150; i++) current.getLogs().add(new GameLogEntry("scale", "log-" + i));
        states.saveAndFlush(current);

        GameStateResponse view = runtime.state("undercover", room.getId(), user(room.getHostUserId()));
        assertEquals(100, view.getLogs().size());
        assertEquals("log-50", view.getLogs().getFirst().getMessage());
        assertEquals("log-149", view.getLogs().getLast().getMessage());
        assertTrue(String.valueOf(view.getExtra().get("viewVersion")).contains(":"));
    }

    @Test void persistedHotLogWindowIsBoundedButOlderPublicEventsRemainReadable() {
        Room room = room(false);
        runtime.start("undercover", room.getId(), user(room.getHostUserId()));
        GameState state = states.findById(room.getId()).orElseThrow();
        String archiveId = String.valueOf(state.getData().get("archiveId"));
        long initialEvents = events.countByArchiveId(archiveId);
        for (int index = 0; index < 150; index++)
            RuleSupport.event(state, "speech", room.getHostUserId(), null, "older-" + index, Map.of());
        states.saveAndFlush(state);
        assertTrue(runtime.acceptSideChat(room.getId(), room.getHostUserId(), "EMOJI", "👍"));
        GameState persisted = states.findById(room.getId()).orElseThrow();
        assertEquals(100, persisted.getLogs().size());
        assertEquals(initialEvents + 151, events.countByArchiveId(archiveId));
        var first = logQuery.page("undercover", room.getId(), room.getHostUserId(), null, 100);
        assertEquals(100, first.items().size());
        assertTrue(first.hasMore());
        var older = logQuery.page("undercover", room.getId(), room.getHostUserId(), first.nextCursor(), 100);
        assertEquals(events.maxPublicSeq(archiveId, GameEventVisibility.PUBLIC) - 100, older.items().size());
        assertTrue(older.items().stream().anyMatch(item -> "older-0".equals(item.getMessage())));
    }

    @Test void newlyAdmittedPrivateLobbyMemberSeesNoPreviousGameData() {
        Room room = room(false); room.setPrivate(true); room.setPassword(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("secret")); rooms.saveAndFlush(room);
        runtime.start("undercover", room.getId(), user(room.getHostUserId()));
        GameState previous = states.findById(room.getId()).orElseThrow(); previous.setPhase("SETTLEMENT"); states.saveAndFlush(previous);
        room = rooms.findById(room.getId()).orElseThrow(); room.setStatus(RoomStatus.WAITING); room.setWaitingSince(java.time.LocalDateTime.now()); room.getSeats().removeLast(); room.syncSeatCount(); rooms.saveAndFlush(room);
        User guest = user(UUID.randomUUID().toString()); roomService.joinRoom(room.getId(), guest.getNickname(), guest, "secret");
        var lobby = facade.state("undercover", room.getId(), guest);
        assertEquals("WAITING", lobby.getPhase()); assertNull(lobby.getMyWord()); assertTrue(lobby.getLogs().isEmpty());
        assertEquals("SETTLEMENT", facade.state("undercover", room.getId(), user(room.getHostUserId())).getPhase());
    }

    private Room room(boolean ai) {
        String id = UUID.randomUUID().toString();
        Room room = new Room(id, "undercover", "V2 integration", RoomStatus.WAITING, 4, false, null, "text", new LinkedHashMap<>(Map.of("playerCount", 4, "speakTime", 0)));
        List<RoomSeat> seats = new ArrayList<>();
        for (int i = 0; i < 4; i++) seats.add(new RoomSeat(i, UUID.randomUUID().toString(), "玩家" + i, ai, ai ? "ai1" : null, "", true, i == 0));
        room.setSeats(seats); room.setHostUserId(seats.get(0).getPlayerId()); room.syncSeatCount();
        return rooms.saveAndFlush(room);
    }
    private AiTurnJob queued(String roomId) { return jobs.findAll().stream().filter(j -> j.getRoomId().equals(roomId) && "QUEUED".equals(j.getStatus())).findFirst().orElseThrow(); }
    private User user(String id) { User user = new User(); user.setId(id); user.setNickname("验收玩家"); return user; }
}
