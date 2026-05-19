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

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Room r where r.id = :roomId")
    Optional<Room> findByIdForUpdate(@Param("roomId") String roomId);

    @Query("select coalesce(sum(r.seatCount), 0) from Room r where r.gameId = :gameId")
    long sumSeatCountByGameId(@Param("gameId") String gameId);
}
