package com.aisocialgame.repository;

import com.aisocialgame.model.AiSafetyControl;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface AiSafetyControlRepository extends JpaRepository<AiSafetyControl, Long> {
    List<AiSafetyControl> findByActiveTrueOrderByIdDesc();
    long countByActiveTrueAndExpiresAtAfterOrActiveTrueAndExpiresAtIsNull(LocalDateTime now);
}
