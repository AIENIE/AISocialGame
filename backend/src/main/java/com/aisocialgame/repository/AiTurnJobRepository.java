package com.aisocialgame.repository;

import com.aisocialgame.model.AiTurnJob;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AiTurnJobRepository extends JpaRepository<AiTurnJob, String> {
    List<AiTurnJob> findByInstanceIdOrderByCreatedAtAsc(String instanceId);
    interface DebugRef {
        String getId(); String getStatus(); java.util.Map<String,Object> getDiagnostics();
    }
    List<DebugRef> findByRoomIdAndInstanceIdOrderByCreatedAtAscIdAsc(String roomId, String instanceId);
    List<AiTurnJob> findTop16ByStatusOrderByCreatedAtAsc(String status);
    /** Recovery polls need clocks, never the large/private observation payload. */
    interface RecoveryCandidate {
        String getId();
        java.util.Map<String, Object> getDiagnostics();
    }
    @Query("select j.id as id, j.diagnostics as diagnostics from AiTurnJob j where j.status = :status order by j.startedAt asc, j.id asc")
    List<RecoveryCandidate> findRecoveryCandidates(@Param("status") String status, org.springframework.data.domain.Pageable page);
    @Query("select j.id as id, j.diagnostics as diagnostics from AiTurnJob j where j.roomId = :roomId and j.status = 'RUNNING'")
    List<RecoveryCandidate> findRunningRecoveryCandidatesInRoom(@Param("roomId") String roomId);
    List<AiTurnJob> findByStatusAndStartedAtBefore(String status, LocalDateTime before);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from AiTurnJob j where j.id = :id")
    Optional<AiTurnJob> findByIdForUpdate(@Param("id") String id);
    List<AiTurnJob> findByStatusOrderByCreatedAtAsc(String status);
    List<AiTurnJob> findByRoomIdAndStatusIn(String roomId, java.util.Collection<String> statuses);
    long countByStatus(String status);
    boolean existsByRoomIdAndStatusIn(String roomId, java.util.Collection<String> statuses);
}
