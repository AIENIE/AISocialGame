package com.aisocialgame.repository;

import com.aisocialgame.model.AiSafetyEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

@Repository
public interface AiSafetyEventRepository extends JpaRepository<AiSafetyEvent, Long>, JpaSpecificationExecutor<AiSafetyEvent> {
    long countByStatusAndSeverity(String status, String severity);
    long countByCreatedAtAfterAndActionIn(LocalDateTime createdAt, java.util.Collection<String> actions);
    long countByCreatedAtAfterAndCategory(LocalDateTime createdAt, String category);
}
