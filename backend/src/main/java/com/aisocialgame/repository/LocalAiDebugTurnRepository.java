package com.aisocialgame.repository;

import com.aisocialgame.model.LocalAiDebugTurn;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface LocalAiDebugTurnRepository extends JpaRepository<LocalAiDebugTurn, String> {
    interface InstanceMetadata {
        String getInstanceId(); LocalDateTime getStartedAt(); long getTurnCount();
    }
    @Query(value = "select t.instanceId as instanceId, min(t.createdAt) as startedAt, count(t) as turnCount from LocalAiDebugTurn t where t.roomId=:roomId group by t.instanceId order by min(t.createdAt) desc, t.instanceId desc",
            countQuery = "select count(distinct t.instanceId) from LocalAiDebugTurn t where t.roomId=:roomId")
    org.springframework.data.domain.Page<InstanceMetadata> instances(@Param("roomId") String roomId, org.springframework.data.domain.Pageable page);
    /** Closed projection: list/summary queries never load prompts or responses. */
    interface Metadata {
        String getId(); String getActorId(); String getActorName(); Integer getSeatNumber(); String getRoleKey();
        String getPhase(); int getRoundNumber(); String getKind(); String getStatus(); String getTerminalReason();
        String getModelKey(); Long getPromptTokens(); Long getCompletionTokens(); Long getCacheReadTokens();
        Long getCacheWriteTokens(); Long getReasoningTokens(); Boolean getUsageAuthoritative(); boolean getUsageComplete();
        int getAttemptCount(); int getResponseCount(); Long getLatencyMs(); LocalDateTime getCreatedAt(); LocalDateTime getUpdatedAt();
    }
    List<Metadata> findByRoomIdAndInstanceIdOrderByCreatedAtAscIdAsc(String roomId, String instanceId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from LocalAiDebugTurn t where t.id = :id")
    Optional<LocalAiDebugTurn> lock(@Param("id") String id);
}
