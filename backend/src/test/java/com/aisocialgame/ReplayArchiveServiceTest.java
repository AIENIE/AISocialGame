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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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

    @Autowired private com.aisocialgame.repository.AiDecisionTraceRepository traces;

    @MockitoBean
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
        Assertions.assertThrows(com.aisocialgame.exception.ApiException.class, () -> replayArchiveService.authorizedEvents(archive.getId(), "GOD", null, "p1"));
        Assertions.assertThrows(com.aisocialgame.exception.ApiException.class, () -> replayArchiveService.authorizedEvents(archive.getId(), "PLAYER", "p1", "stranger"));
        Assertions.assertThrows(com.aisocialgame.exception.ApiException.class, () -> replayArchiveService.authorizedEvents(archive.getId(), "PLAYER", null, null));
        Assertions.assertEquals(List.of("PUBLIC", "PLAYER"), replayArchiveService.authorizedEvents(archive.getId(), "PLAYER", null, "p1").getAvailableViews());
        Assertions.assertEquals(1, replayArchiveService.search(null, "p1", null, null, 0, 10, "p1").getTotal());
        Assertions.assertEquals(0, replayArchiveService.search(null, "stranger", null, null, 0, 10, "stranger").getTotal());
    }

    @Test void oneRoomMultipleInstancesNeverShareQualityAndFiltersPrecedePagination() throws Exception {
        String roomId="multi-"+java.util.UUID.randomUUID();String owner="owner-"+roomId;
        var room=new Room(roomId,"undercover","multi",RoomStatus.PLAYING,4,false,null,"text",Map.of());
        var oldTrace=new com.aisocialgame.model.AiDecisionTrace();oldTrace.setAction("SPEAK");oldTrace.setRoomId(roomId);oldTrace.setGameId("undercover");oldTrace.setQuality(Map.of());traces.saveAndFlush(oldTrace);
        for(int n=0;n<3;n++) {
            String instance="instance-"+roomId+"-"+n;
            var trace=new com.aisocialgame.model.AiDecisionTrace();trace.setAction("SPEAK");trace.setRoomId(roomId);trace.setInstanceId(instance);trace.setGameId("undercover");trace.setQuality(Map.of("instanceId",instance));trace.setFallback(n==1);traces.saveAndFlush(trace);
            var state=new GameState(roomId,"undercover","SETTLEMENT");state.getData().put("archiveId",instance);state.getData().put("winner","DRAW");
            state.setPlayers(List.of(player(n==1?"other-"+owner:owner,"name","BLANK","PRIVATE_WORD",false)));
            var archive=replayArchiveService.archiveFinishedGame(state,room);archive.setFinishedAt(LocalDateTime.of(2026,9,20+n,12,0));gameArchiveRepository.saveAndFlush(archive);
            Assertions.assertEquals(1L,archive.getAiQualitySummary().get("traceCount"));Assertions.assertEquals(n==1?1L:0L,archive.getAiQualitySummary().get("fallbackCount"));
            Assertions.assertEquals(1L,archive.getAiQualitySummary().get("legacyUnattributed"));
            String json=new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules().writeValueAsString(replayArchiveService.detail(instance, n==1?"other-"+owner:owner));Assertions.assertFalse(json.contains("PRIVATE_WORD"));Assertions.assertFalse(json.contains("BLANK"));
        }
        var first=replayArchiveService.search("undercover",owner,null,null,0,1,owner);var second=replayArchiveService.search("undercover",owner,null,null,1,1,owner);
        Assertions.assertEquals(2,first.getTotal());Assertions.assertNotEquals(first.getItems().getFirst().getId(),second.getItems().getFirst().getId());
        Assertions.assertEquals(1,replayArchiveService.search("undercover",owner,LocalDateTime.of(2026,9,21,0,0),null,0,20,owner).getTotal());
    }

    private GamePlayerState player(String playerId, String name, String role, String word, boolean ai) {
        GamePlayerState player = new GamePlayerState(playerId, name, ai ? 2 : 1, ai, ai ? "ai1" : null, null);
        player.setRole(role);
        player.setWord(word);
        player.setAlive(true);
        return player;
    }

    @Test void accessIsFrozenAtStartAndUnknownHistoryNeverBecomesPublic() {
        for (Boolean visible : java.util.Arrays.asList(false, true, null)) {
            String id = "acl-" + java.util.UUID.randomUUID();
            var room = new Room(id, "undercover", "ACL", RoomStatus.PLAYING, 4, !Boolean.TRUE.equals(visible), null, "text", Map.of());
            room.setHostUserId("original-host");
            var state = new GameState(id, "undercover", "SETTLEMENT");
            state.setPlayers(List.of(player("original-player", "participant", "CIVILIAN", "secret", false)));
            state.getData().put("archiveId", id);
            if (visible != null) com.aisocialgame.service.RoomAccessPolicy.snapshot(state, room);
            room.setPrivate(false);
            room.setHostUserId("new-host");
            replayArchiveService.archiveFinishedGame(state, room);
            Assertions.assertNotNull(replayArchiveService.detail(id, "original-player"));
            if (visible != null) Assertions.assertNotNull(replayArchiveService.detail(id, "original-host"));
            if (Boolean.TRUE.equals(visible)) Assertions.assertNotNull(replayArchiveService.detail(id, "spectator"));
            else {
                Assertions.assertThrows(com.aisocialgame.exception.ApiException.class, () -> replayArchiveService.detail(id, "new-host"));
                Assertions.assertThrows(com.aisocialgame.exception.ApiException.class, () -> replayArchiveService.authorizedEvents(id, "PUBLIC", null, "spectator"));
            }
            Assertions.assertThrows(com.aisocialgame.exception.ApiException.class, () -> replayArchiveService.detail(id, null));
        }
    }
}
