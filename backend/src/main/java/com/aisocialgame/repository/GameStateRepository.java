package com.aisocialgame.repository;

import com.aisocialgame.model.GameState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface GameStateRepository extends JpaRepository<GameState, String> {
    /** Routing must not preload a managed GameState in an open web persistence context. */
    @org.springframework.data.jpa.repository.Query("select s.gameId as gameId, s.phase as phase, s.data as data from GameState s where s.roomId = :roomId")
    java.util.Optional<RoutingSnapshot> findRoutingSnapshotByRoomId(@org.springframework.data.repository.query.Param("roomId") String roomId);

    interface RoutingSnapshot {
        String getGameId();
        String getPhase();
        java.util.Map<String, Object> getData();
    }

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select s from GameState s where s.roomId = :roomId")
    java.util.Optional<GameState> findByIdForUpdate(@org.springframework.data.repository.query.Param("roomId") String roomId);

    @org.springframework.data.jpa.repository.Query(value = "SELECT * FROM game_states WHERE room_id=:roomId FOR UPDATE SKIP LOCKED", nativeQuery = true)
    java.util.Optional<GameState> findByIdForUpdateSkipLocked(@org.springframework.data.repository.query.Param("roomId") String roomId);

    @org.springframework.data.jpa.repository.Query("select s.roomId from GameState s where s.phase <> 'SETTLEMENT' and s.roomId > :after order by s.roomId")
    java.util.List<String> findActiveRoomIdsAfter(@org.springframework.data.repository.query.Param("after") String after,
                                                   org.springframework.data.domain.Pageable pageable);
}
