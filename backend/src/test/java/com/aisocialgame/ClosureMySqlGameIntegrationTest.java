package com.aisocialgame;

import com.aisocialgame.model.GameState;
import com.aisocialgame.model.Room;
import com.aisocialgame.model.RoomStatus;
import com.aisocialgame.repository.GameStateRepository;
import com.aisocialgame.repository.RoomRepository;
import com.aisocialgame.service.v2.V2GameService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import com.aisocialgame.migration.ClosureMySqlSupport;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Reuses all runtime rollback, idempotency and privacy assertions against real MySQL; RPC stays mocked. */
@EnabledIfEnvironmentVariable(named="AIENIE_CLOSURE_MYSQL",matches="1")
class ClosureMySqlGameIntegrationTest extends V2GameServiceIntegrationTest {
    @DynamicPropertySource static void mysql(DynamicPropertyRegistry r){ClosureMySqlSupport.properties(r);}

    @Autowired private RoomRepository clockRooms;
    @Autowired private GameStateRepository clockStates;
    @Autowired private V2GameService clockRuntime;

    @Test void lockedRoomAndStateAreSkippedWithoutBlockingClock() throws Exception {
        String roomId = UUID.randomUUID().toString();
        clockRooms.saveAndFlush(new Room(roomId, "undercover", "clock", RoomStatus.PLAYING, 4,
                false, null, "text", Map.of()));
        GameState state = new GameState(roomId, "undercover", "DESCRIPTION");
        state.getData().put("ruleVersion", 2);
        clockStates.saveAndFlush(state);
        for (String lockedTable : java.util.List.of("rooms", "game_states")) {
            try (var lock = ClosureMySqlSupport.connect("runtime")) {
                lock.setAutoCommit(false);
                String sql = lockedTable.equals("rooms")
                        ? "SELECT id FROM rooms WHERE id=? FOR UPDATE"
                        : "SELECT room_id FROM game_states WHERE room_id=? FOR UPDATE";
                try (var statement = lock.prepareStatement(sql)) {
                    statement.setString(1, roomId);
                    try (var rows = statement.executeQuery()) { assertTrue(rows.next()); }
                }
                CompletableFuture<Void> clock = CompletableFuture.runAsync(() -> clockRuntime.tick(roomId));
                try { clock.get(2, TimeUnit.SECONDS); }
                finally { lock.rollback(); }
            }
        }
        ClosureMySqlSupport.evidence("clock-skip-locked", Map.of("lockedRoomSkipped", true,
                "lockedStateSkipped", true));
    }
}
