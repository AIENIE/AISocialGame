package com.aisocialgame.repository;

import com.aisocialgame.model.AiDecisionTrace;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

@Repository
public interface AiDecisionTraceRepository extends JpaRepository<AiDecisionTrace, Long>, JpaSpecificationExecutor<AiDecisionTrace> {
    boolean existsByRoomIdAndActionAndPersonaId(String roomId, String action, String personaId);
}
