package com.aisocialgame.service.v2;

import com.aisocialgame.model.AiTurnJob;
import com.aisocialgame.engine.v2.GameRuleSet;
import com.aisocialgame.repository.AiTurnJobRepository;
import com.aisocialgame.repository.GameStateRepository;
import com.aisocialgame.service.ai.v2.AiTurnGenerator;
import com.aisocialgame.service.ai.v2.AiTurnDecision;
import com.aisocialgame.service.ai.v2.VisibleObservation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Dispatch failures must not consume worker capacity after the failure has ended. */
class AiTurnCoordinatorTest {
    private final AiTurnJobRepository jobs = mock(AiTurnJobRepository.class);
    private final GameStateRepository states = mock(GameStateRepository.class);
    private final V2GameService runtime = mock(V2GameService.class);
    private final AiTurnGenerator generator = mock(AiTurnGenerator.class);
    private final ExecutorService executor = mock(ExecutorService.class);
    private final Deque<Runnable> accepted = new ArrayDeque<>();
    private AiTurnCoordinator coordinator;

    @BeforeEach void setup() {
        coordinator = spy(new AiTurnCoordinator(jobs, states, runtime, generator, mock(PlatformTransactionManager.class), 1, true));
        ExecutorService original = (ExecutorService) ReflectionTestUtils.getField(coordinator, "executor");
        assertNotNull(original);
        original.shutdownNow();
        ReflectionTestUtils.setField(coordinator, "executor", executor);
        when(states.findActiveRoomIdsAfter(anyString(), any(Pageable.class))).thenReturn(List.of());
        when(jobs.findRecoveryCandidates(eq("RUNNING"), any())).thenReturn(List.of());
        doNothing().when(coordinator).run(any(AiTurnJob.class));
        doAnswer(invocation -> { accepted.addLast(invocation.getArgument(0)); return null; }).when(executor).execute(any(Runnable.class));
    }

    @AfterEach void close() {
        coordinator.close();
    }

    @Test void claimExceptionReturnsPermitAndTheNextPulseCanDispatchWithoutRestart() {
        AiTurnJob job = job("retry-after-claim-failure");
        when(jobs.findTop16ByStatusOrderByCreatedAtAsc("QUEUED")).thenReturn(List.of(job));
        doThrow(new IllegalStateException("temporary database failure")).doReturn(job).when(coordinator).claim(job.getId());

        assertDoesNotThrow(coordinator::pulse);
        assertTrue(accepted.isEmpty());
        verify(executor, never()).execute(any(Runnable.class));

        coordinator.pulse();
        assertEquals(1, accepted.size(), "capacity is available again after the failed claim");
        coordinator.pulse();
        verify(coordinator, times(2)).claim(job.getId());
        assertEquals(1, accepted.size(), "the accepted worker still holds the only permit");

        accepted.removeFirst().run();
        coordinator.pulse();
        verify(coordinator, times(3)).claim(job.getId());
        assertEquals(1, accepted.size(), "normal completion returns the permit exactly once");
        verifyNoInteractions(runtime, generator);
    }

    @Test void executorRejectionReturnsPermitAndRecoversClaimedJobEvenWhenRecoveryIsTemporarilyUnavailable() {
        AiTurnJob rejected = job("rejected");
        AiTurnJob next = job("next");
        AiTurnJob last = job("last");
        when(jobs.findTop16ByStatusOrderByCreatedAtAsc("QUEUED"))
                .thenReturn(List.of(rejected), List.of(next), List.of(last));
        doReturn(rejected).when(coordinator).claim(rejected.getId());
        doReturn(next).when(coordinator).claim(next.getId());
        doReturn(last).when(coordinator).claim(last.getId());
        doThrow(new RejectedExecutionException("executor temporarily unavailable"))
                .doAnswer(invocation -> { accepted.addLast(invocation.getArgument(0)); return null; })
                .when(executor).execute(any(Runnable.class));
        doThrow(new IllegalStateException("recovery will be retried by the abandoned-job scan")).when(runtime).fail(eq(rejected.getId()), eq("DISPATCH_REJECTED"), anyMap());

        assertDoesNotThrow(coordinator::pulse);
        assertTrue(accepted.isEmpty());
        verify(runtime).fail(eq(rejected.getId()), eq("DISPATCH_REJECTED"), anyMap());
        verify(coordinator, never()).run(rejected);

        coordinator.pulse();
        assertEquals(1, accepted.size(), "rejected delivery returns the permit even if recovery throws");
        coordinator.pulse();
        verify(coordinator, never()).claim(last.getId());
        assertEquals(1, accepted.size(), "there is no double release after rejection");

        accepted.removeFirst().run();
        coordinator.pulse();
        verify(coordinator).run(next);
        verify(coordinator).claim(last.getId());
        assertEquals(1, accepted.size());
        verifyNoInteractions(generator);
    }

    @Test void expiredOrObsoletePersistedOpportunityDoesNotReachTheGenerator() {
        AiTurnJob job = job("expired-window");
        job.setObservation(Map.of("gameId", "undercover"));
        doCallRealMethod().when(coordinator).run(job);
        when(runtime.remainingTurnMillis(job.getId())).thenReturn(0L);

        coordinator.run(job);

        verify(runtime).remainingTurnMillis(job.getId());
        verify(runtime).fail(eq(job.getId()), eq("NO_ACTION_BUDGET"), anyMap());
        verify(runtime, never()).complete(anyString(), any());
        verifyNoInteractions(generator);
    }

    @Test void currentWindowBudgetIsPassedToTheGeneratorBeforeCompletion() {
        AiTurnJob job = job("short-window");
        job.setObservation(Map.of("gameId", "undercover"));
        doCallRealMethod().when(coordinator).run(job);
        GameRuleSet rules = mock(GameRuleSet.class);
        AiTurnDecision result = AiTurnDecision.fallback(new com.aisocialgame.dto.PlayerAction());
        when(runtime.rules("undercover")).thenReturn(rules);
        when(runtime.remainingTurnMillis(job.getId())).thenReturn(12_345L);
        when(generator.generate(eq(rules), any(VisibleObservation.class), eq(job.getId()), eq(12_345L))).thenReturn(result);

        coordinator.run(job);

        var ordered = inOrder(runtime, generator);
        ordered.verify(runtime).rules("undercover");
        ordered.verify(runtime).remainingTurnMillis(job.getId());
        ordered.verify(generator).generate(eq(rules), any(VisibleObservation.class), eq(job.getId()), eq(12_345L));
        ordered.verify(runtime).complete(job.getId(), result);
        verify(runtime, never()).fail(anyString());
    }

    @Test void submissionFailurePreservesGenerationMeasurementsForRecovery() {
        AiTurnJob job = job("rollback-recovery"); job.setObservation(Map.of("gameId", "undercover"));
        doCallRealMethod().when(coordinator).run(job);
        GameRuleSet rules = mock(GameRuleSet.class); when(runtime.rules("undercover")).thenReturn(rules);
        when(runtime.remainingTurnMillis(job.getId())).thenReturn(50_000L);
        var result = new AiTurnDecision(new com.aisocialgame.dto.PlayerAction(), "", Map.of(), List.of(), Map.of(), false, Map.of("latencyMs", 123L));
        when(generator.generate(eq(rules), any(), eq(job.getId()), eq(50_000L))).thenReturn(result);
        doThrow(new IllegalStateException("private diagnostic must not persist")).when(runtime).complete(job.getId(), result);
        coordinator.run(job);
        verify(runtime).fail(eq(job.getId()), eq("SUBMISSION_EXCEPTION"), argThat(d -> Long.valueOf(123L).equals(d.get("latencyMs")) && d.containsKey("submissionFailedMs")));
    }

    @Test void recoveryUsesEpochRatherThanLocalDateTimeAndKeepsSixtySecondThreshold() {
        AiTurnJob old = job("epoch-old"), young = job("epoch-young");
        old.getDiagnostics().put("startedEpochMs", java.time.Instant.now().minusSeconds(61).toEpochMilli());
        old.setStartedAt(java.time.LocalDateTime.now().plusHours(8));
        young.getDiagnostics().put("startedEpochMs", java.time.Instant.now().toEpochMilli());
        young.setStartedAt(java.time.LocalDateTime.now().minusHours(8));
        when(jobs.findRecoveryCandidates(eq("RUNNING"), any())).thenReturn(List.of(recovery(old), recovery(young)));
        coordinator.pulse();
        verify(runtime).fail(eq(old.getId()), eq("RECOVERY_TIMEOUT"), anyMap());
        verify(runtime, never()).fail(eq(young.getId()), anyString(), anyMap());
    }

    @Test void blockedRoomClockDoesNotDelayOtherRoomOrJobRecovery() throws Exception {
        var blocked = new java.util.concurrent.CountDownLatch(1);
        var entered = new java.util.concurrent.CountDownLatch(1);
        var fast = new java.util.concurrent.CountDownLatch(1);
        when(states.findActiveRoomIdsAfter(anyString(), any(Pageable.class)))
                .thenReturn(List.of("slow-room", "fast-room"));
        doAnswer(invocation -> {
            String roomId = invocation.getArgument(0);
            if (roomId.equals("slow-room")) {
                entered.countDown();
                blocked.await(3, java.util.concurrent.TimeUnit.SECONDS);
            } else fast.countDown();
            return null;
        }).when(runtime).tick(anyString());
        AiTurnJob overdue = job("overdue-while-clock-blocked");
        overdue.getDiagnostics().put("startedEpochMs", java.time.Instant.now().minusSeconds(90).toEpochMilli());
        when(jobs.findRecoveryCandidates(eq("RUNNING"), any())).thenReturn(List.of(recovery(overdue)));
        try {
            coordinator.clockPulse();
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(fast.await(2, java.util.concurrent.TimeUnit.SECONDS));
            coordinator.clockPulse();
            verify(runtime, times(1)).tick("slow-room");
            coordinator.recoveryPulse();
            verify(runtime).fail(eq(overdue.getId()), eq("RECOVERY_TIMEOUT"), anyMap());
        } finally {
            blocked.countDown();
        }
    }

    private AiTurnJobRepository.RecoveryCandidate recovery(AiTurnJob job) {
        return new AiTurnJobRepository.RecoveryCandidate() {
            public String getId() { return job.getId(); }
            public java.time.LocalDateTime getStartedAt() { return job.getStartedAt(); }
            public Map<String, Object> getDiagnostics() { return job.getDiagnostics(); }
        };
    }

    private AiTurnJob job(String id) {
        AiTurnJob job = new AiTurnJob();
        job.setId(id);
        job.setRoomId("room-" + id);
        return job;
    }
}
