package com.aisocialgame;

import com.aisocialgame.integration.grpc.client.AiGrpcClient;
import com.aisocialgame.model.GameEventVisibility;
import com.aisocialgame.model.GamePlayerState;
import com.aisocialgame.model.GameState;
import com.aisocialgame.model.Room;
import com.aisocialgame.model.RoomStatus;
import com.aisocialgame.repository.GameArchiveRepository;
import com.aisocialgame.repository.GameEventRepository;
import com.aisocialgame.service.GameEventRecorder;
import com.aisocialgame.service.ReplayArchiveService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@SpringBootTest(classes = AiSocialGameApplication.class)
@ActiveProfiles("test")
class ReplayArchiveServiceTest {
    @Autowired
    private GameEventRecorder gameEventRecorder;

    @Autowired
    private ReplayArchiveService replayArchiveService;

    @Autowired
    private GameEventRepository gameEventRepository;

    @Autowired
    private GameArchiveRepository gameArchiveRepository;

    @MockBean
    private AiGrpcClient aiGrpcClient;

    @Test
    void archivesFinishedGameAndFiltersReplayVisibility() {
        GameState state = new GameState("replay-room-1", "undercover", "SETTLEMENT");
        state.setCreatedAt(LocalDateTime.now().minusMinutes(3));
        state.setData(new HashMap<>(Map.of("winner", "CIVILIAN")));
        state.setPlayers(List.of(
                player("p1", "玩家1", "CIVILIAN", "苹果", false),
                player("ai1", "AI玩家", "UNDERCOVER", "梨子", true)
        ));
        Room room = new Room("replay-room-1", "undercover", "回放测试房", RoomStatus.PLAYING, 4, false, null, "text", Map.of("playerCount", 4));

        gameEventRecorder.recordPublic(state, "GAME_START", null, null, Map.of("message", "开始"));
        gameEventRecorder.recordPrivate(state, "WORD_ASSIGNED", "p1", null, List.of("p1"), Map.of("word", "苹果"));
        gameEventRecorder.recordGod(state, "ROLE_ASSIGNED", "ai1", null, Map.of("role", "UNDERCOVER", "word", "梨子"));
        gameEventRecorder.recordPublic(state, "GAME_END", null, null, Map.of("winner", "CIVILIAN"));

        var archive = replayArchiveService.archiveFinishedGame(state, room);

        Assertions.assertNotNull(archive);
        Assertions.assertTrue(gameArchiveRepository.existsById(archive.getId()));
        Assertions.assertEquals(4, gameEventRepository.countByArchiveId(archive.getId()));

        var publicReplay = replayArchiveService.events(archive.getId(), "PUBLIC", null);
        Assertions.assertEquals(List.of("GAME_START", "GAME_END"), publicReplay.getEvents().stream().map(e -> e.getEventType()).toList());

        var playerReplay = replayArchiveService.events(archive.getId(), "PLAYER", "p1");
        Assertions.assertTrue(playerReplay.getEvents().stream().anyMatch(e -> e.getEventType().equals("WORD_ASSIGNED")));
        Assertions.assertTrue(playerReplay.getEvents().stream().noneMatch(e -> e.getVisibility() == GameEventVisibility.GOD));

        var godReplay = replayArchiveService.events(archive.getId(), "GOD", null);
        Assertions.assertEquals(List.of(1, 2, 3, 4), godReplay.getEvents().stream().map(e -> e.getSeq()).toList());
        Assertions.assertTrue(godReplay.getEvents().stream().anyMatch(e -> e.getEventType().equals("ROLE_ASSIGNED")));
    }

    private GamePlayerState player(String playerId, String name, String role, String word, boolean ai) {
        GamePlayerState player = new GamePlayerState(playerId, name, ai ? 2 : 1, ai, ai ? "ai1" : null, null);
        player.setRole(role);
        player.setWord(word);
        player.setAlive(true);
        return player;
    }
}
