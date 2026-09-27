package com.aisocialgame;

import com.aisocialgame.exception.ApiException;
import com.aisocialgame.model.GameEvent;
import com.aisocialgame.model.GameEventVisibility;
import com.aisocialgame.model.GameState;
import com.aisocialgame.model.Room;
import com.aisocialgame.model.RoomStatus;
import com.aisocialgame.repository.GameEventRepository;
import com.aisocialgame.repository.GameStateRepository;
import com.aisocialgame.repository.RoomRepository;
import com.aisocialgame.service.GameLogQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class GameLogQueryServiceTest {
    @Autowired RoomRepository rooms;
    @Autowired GameStateRepository states;
    @Autowired GameEventRepository events;
    @Autowired GameLogQueryService logs;

    @Test void cursorIsContiguousForPublicEventsAndPermissionIsCheckedFirst() {
        String roomId = UUID.randomUUID().toString();
        String archiveId = "archive-" + UUID.randomUUID();
        String hostId = UUID.randomUUID().toString();
        Room room = new Room(roomId, "undercover", "private", RoomStatus.PLAYING, 4, true, null, "text", Map.of());
        room.setHostUserId(hostId);
        rooms.saveAndFlush(room);
        GameState state = new GameState(roomId, "undercover", "DESCRIPTION");
        state.getData().put("archiveId", archiveId);
        state.getData().put("recordedEventCount", 4);
        state.getData().put("ruleVersion", 2);
        states.saveAndFlush(state);
        events.saveAllAndFlush(List.of(event(archiveId, roomId, 1, 1L, GameEventVisibility.PUBLIC, "first"),
                event(archiveId, roomId, 2, null, GameEventVisibility.PRIVATE, "secret"),
                event(archiveId, roomId, 3, 2L, GameEventVisibility.PUBLIC, "second"),
                event(archiveId, roomId, 4, 3L, GameEventVisibility.PUBLIC, "third")));

        var newest = logs.page("undercover", roomId, hostId, null, 2);
        assertEquals(List.of("second", "third"), newest.items().stream().map(item -> item.getMessage()).toList());
        assertEquals(2L, newest.nextCursor());
        assertTrue(newest.hasMore());
        var older = logs.page("undercover", roomId, hostId, newest.nextCursor(), 100);
        assertEquals(List.of("first"), older.items().stream().map(item -> item.getMessage()).toList());
        assertFalse(older.hasMore());
        assertNull(older.nextCursor());

        ApiException denied = assertThrows(ApiException.class, () -> logs.page("undercover", roomId,
                UUID.randomUUID().toString(), null, 100));
        assertEquals(HttpStatus.FORBIDDEN, denied.getStatus());
    }

    private GameEvent event(String archiveId, String roomId, int seq, Long publicSeq,
                            GameEventVisibility visibility, String message) {
        GameEvent event = new GameEvent();
        event.setArchiveId(archiveId);
        event.setRoomId(roomId);
        event.setGameId("undercover");
        event.setSeq(seq);
        event.setPublicSeq(publicSeq);
        event.setEventType("speech");
        event.setVisibility(visibility);
        event.setData(Map.of("message", message, "eventId", UUID.randomUUID().toString()));
        return event;
    }
}
