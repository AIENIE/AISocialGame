package com.aisocialgame.repository;

import com.aisocialgame.model.AiCallUsage;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;

public interface AiCallUsageRepository extends JpaRepository<AiCallUsage, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from AiCallUsage u where u.id=:id")
    Optional<AiCallUsage> lock(@Param("id") String id);
    List<AiCallUsage> findByCompletedEpochMsGreaterThanEqualAndAdmittedTrue(long since);
    List<AiCallUsage> findByStartedEpochMsGreaterThanEqualAndAdmittedTrue(long since);
}
