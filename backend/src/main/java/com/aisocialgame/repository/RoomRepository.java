package com.aisocialgame.repository;

import com.aisocialgame.model.Room;
import com.aisocialgame.model.RoomStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RoomRepository extends JpaRepository<Room, String> {
    List<Room> findByGameIdOrderByCreatedAtAsc(String gameId);

    Page<Room> findByGameIdAndStatusOrderByCreatedAtDesc(String gameId, RoomStatus status, Pageable pageable);

    Page<Room> findByGameIdOrderByCreatedAtDesc(String gameId, Pageable pageable);

    Optional<Room> findByRoomCode(String roomCode);
    boolean existsByRoomCode(String roomCode);

    @Query("select r from Room r where r.gameId=:gameId and r.isPrivate=false and r.status<>com.aisocialgame.model.RoomStatus.EXPIRED "
            + "and (:status is null or r.status=:status) and (r.status<>com.aisocialgame.model.RoomStatus.WAITING or r.waitingSince>:cutoff) order by r.createdAt desc")
    Page<Room> findDiscoverable(@Param("gameId") String gameId, @Param("status") RoomStatus status,
                               @Param("cutoff") java.time.LocalDateTime cutoff, Pageable pageable);

    @Query("select r.id from Room r where r.status=com.aisocialgame.model.RoomStatus.WAITING and (r.waitingSince is null or r.waitingSince<=:cutoff) order by r.waitingSince")
    List<String> findExpirationCandidates(@Param("cutoff") java.time.LocalDateTime cutoff, Pageable pageable);

    interface JoinSnapshot { String getId(); String getPassword(); boolean getPrivateRoom(); }
    @Query("select r.id as id, r.password as password, r.isPrivate as privateRoom from Room r where r.id=:roomId")
    Optional<JoinSnapshot> findJoinSnapshot(@Param("roomId") String roomId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Room r where r.id = :roomId")
    Optional<Room> findByIdForUpdate(@Param("roomId") String roomId);

    @Query(value = "SELECT * FROM rooms WHERE id=:roomId FOR UPDATE SKIP LOCKED", nativeQuery = true)
    Optional<Room> findByIdForUpdateSkipLocked(@Param("roomId") String roomId);

    @Query("select coalesce(sum(r.seatCount), 0) from Room r where r.gameId = :gameId")
    long sumSeatCountByGameId(@Param("gameId") String gameId);
}
