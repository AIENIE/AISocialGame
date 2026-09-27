package com.aisocialgame.service.v2;

import com.aisocialgame.engine.v2.GameRuleSet;
import com.aisocialgame.engine.v2.TurnRequest;
import com.aisocialgame.model.AiTurnJob;
import com.aisocialgame.model.GameState;
import com.aisocialgame.repository.*;
import com.aisocialgame.service.*;
import com.aisocialgame.service.ai.v2.*;
import com.aisocialgame.service.safety.AiSafetyService;
import com.aisocialgame.websocket.GamePushService;
import com.aisocialgame.websocket.PlayerConnectionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class V2GameServiceDeadlineTest {
    private final AiTurnJobRepository jobs = mock(AiTurnJobRepository.class);
    private final GameStateRepository states = mock(GameStateRepository.class);
    private final GameRuleSet rules = mock(GameRuleSet.class);
    private V2GameService runtime;
    private AiTurnJob job;
    private GameState state;

    @BeforeEach void setup() {
        when(rules.gameId()).thenReturn("undercover");
        runtime = new V2GameService(List.of(rules), mock(RoomRepository.class), states, jobs,
                mock(PlatformTransactionManager.class), mock(ObservationFactory.class), mock(AiMemoryServiceV2.class),
                mock(GameEventRecorder.class), mock(ReplayArchiveService.class), mock(StatsService.class),
                mock(GamePushService.class), mock(PlayerConnectionService.class), mock(AiSafetyService.class),
                mock(AiDecisionTraceRepository.class), true);
        job = new AiTurnJob();
        job.setId("job"); job.setRoomId("room"); job.setInstanceId("instance"); job.setStatus("RUNNING");
        job.setActorId("ai-one"); job.setKind("SPEAK"); job.setTurnKey("opportunity-one");
        state = new GameState("room", "undercover", "DESCRIPTION");
        state.setData(new LinkedHashMap<>(Map.of("archiveId", "instance", "ruleVersion", 2)));
        when(jobs.findById("job")).thenReturn(Optional.of(job));
        when(states.findById("room")).thenReturn(Optional.of(state));
        when(rules.isTurnCurrent(state, new TurnRequest("ai-one", "SPEAK", "opportunity-one"))).thenReturn(true);
    }

    @Test void deadlineBudgetHasOneSecondHeadroomAndNeverExtendsTheWindow() {
        LocalDateTime now = LocalDateTime.of(2030, 1, 1, 12, 0);
        assertEquals(50_000, V2GameService.turnBudgetMillis(null, now));
        assertEquals(50_000, V2GameService.turnBudgetMillis(now.plusMinutes(5), now));
        assertEquals(6_500, V2GameService.turnBudgetMillis(now.plusNanos(7_500_000_000L), now));
        assertEquals(0, V2GameService.turnBudgetMillis(now.plusSeconds(1), now));
        assertEquals(0, V2GameService.turnBudgetMillis(now.plusNanos(999_000_000), now));
        assertEquals(0, V2GameService.turnBudgetMillis(now, now));
        assertEquals(0, V2GameService.turnBudgetMillis(now.minusSeconds(1), now));
    }

    @Test void onlyThePersistedRunningCurrentOpportunityReceivesABudget() {
        assertEquals(50_000, runtime.remainingTurnMillis("job"));
        job.setStatus("SUCCEEDED");
        assertEquals(0, runtime.remainingTurnMillis("job"));
        job.setStatus("RUNNING");
        state.getData().put("archiveId", "restarted-instance");
        assertEquals(0, runtime.remainingTurnMillis("job"));
        state.getData().put("archiveId", "instance");
        when(rules.isTurnCurrent(state, new TurnRequest("ai-one", "SPEAK", "opportunity-one"))).thenReturn(false);
        assertEquals(0, runtime.remainingTurnMillis("job"));
    }

    @Test void aMatchingOpportunityAlreadyExpiredWithoutATickReceivesNoBudget() {
        state.setPhaseEndsAt(LocalDateTime.now().minusSeconds(1));
        assertEquals(0, runtime.remainingTurnMillis("job"));
        verify(jobs, never()).save(any());
        verify(states, never()).save(any());
    }
}
