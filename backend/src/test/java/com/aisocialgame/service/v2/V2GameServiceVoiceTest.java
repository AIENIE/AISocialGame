package com.aisocialgame.service.v2;

import com.aisocialgame.config.AppProperties;
import com.aisocialgame.engine.v2.GameRuleSet;
import com.aisocialgame.engine.v2.LegalAction;
import com.aisocialgame.exception.ApiException;
import com.aisocialgame.model.GamePlayerState;
import com.aisocialgame.model.GameState;
import com.aisocialgame.model.Room;
import com.aisocialgame.model.RoomStatus;
import com.aisocialgame.model.User;
import com.aisocialgame.repository.*;
import com.aisocialgame.service.*;
import com.aisocialgame.service.ai.v2.*;
import com.aisocialgame.service.safety.AiSafetyService;
import com.aisocialgame.websocket.GamePushService;
import com.aisocialgame.websocket.PlayerConnectionService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class V2GameServiceVoiceTest {
    @Test void onlyTheCurrentParticipantWithAnUnexpiredTextActionCanTranscribe() {
        var room = new Room("room", "undercover", "测试", RoomStatus.PLAYING, 4, false, null, "TEXT", null);
        var state = new GameState("room", "undercover", "DESCRIPTION");
        state.setPlayers(List.of(new GamePlayerState("player", "玩家", 0, false, null, "")));
        state.getData().put("archiveId", "archive");
        state.setPhaseEndsAt(LocalDateTime.now().plusMinutes(1));
        var rules = mock(GameRuleSet.class);
        when(rules.gameId()).thenReturn("undercover");
        when(rules.legalActions(state, "player")).thenReturn(List.of(LegalAction.text("SPEAK", "发言", 90)));
        var states = mock(GameStateRepository.class);
        when(states.findById("room")).thenReturn(Optional.of(state));
        var lifecycle = mock(RoomLifecycle.class);
        when(lifecycle.requireActive("room")).thenReturn(room);
        var runtime = new V2GameService(List.of(rules), mock(RoomRepository.class), states, mock(AiTurnJobRepository.class),
                mock(PlatformTransactionManager.class), mock(ObservationFactory.class), mock(AiMemoryServiceV2.class),
                mock(GameEventRecorder.class), mock(ReplayArchiveService.class), mock(StatsService.class),
                mock(GamePushService.class), mock(PlayerConnectionService.class), mock(AiSafetyService.class),
                mock(AiDecisionTraceRepository.class));
        var settings = new AppProperties();
        settings.getAi().setVoiceInputEnabled(true);
        ReflectionTestUtils.setField(runtime, "lifecycle", lifecycle);
        ReflectionTestUtils.setField(runtime, "appProperties", settings);
        ReflectionTestUtils.setField(runtime, "entityManager", mock(EntityManager.class));
        var user = new User();
        user.setId("player");
        var extra = runtime.state("undercover", "room", user).getExtra();
        String phaseToken = (String) extra.get("phaseToken");

        assertEquals(true, extra.get("voiceInputEnabled"));
        assertEquals("archive", runtime.requireVoiceOpportunity("undercover", "room", "player", phaseToken, "SPEAK:").archiveId());
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ApiException.class,
                () -> runtime.requireVoiceOpportunity("undercover", "room", "spectator", phaseToken, "SPEAK:")).getStatus());
        assertEquals("PHASE_CHANGED", assertThrows(ApiException.class,
                () -> runtime.requireVoiceOpportunity("undercover", "room", "player", phaseToken, "VOTE:")).getCode());
        state.setPhaseEndsAt(LocalDateTime.now().minusSeconds(1));
        assertEquals("PHASE_CHANGED", assertThrows(ApiException.class,
                () -> runtime.requireVoiceOpportunity("undercover", "room", "player", phaseToken, "SPEAK:")).getCode());
        verify(states, never()).save(any());
    }
}
