package com.aisocialgame.repository;

import com.aisocialgame.model.LocalAiDebugTurn;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface LocalAiDebugTurnRepository extends JpaRepository<LocalAiDebugTurn, String> {
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
