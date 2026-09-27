package com.aisocialgame.repository;

import com.aisocialgame.model.AiCallBudget;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface AiCallBudgetRepository extends JpaRepository<AiCallBudget, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from AiCallBudget b where b.id = :id")
    Optional<AiCallBudget> findByIdForUpdate(@Param("id") String id);
}
