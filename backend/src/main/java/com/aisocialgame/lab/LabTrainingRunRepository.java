package com.aisocialgame.lab;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.*;
public interface LabTrainingRunRepository extends JpaRepository<LabTrainingRun,String> {
    Optional<LabTrainingRun> findByExperimentIdAndOperationId(String experimentId,String operationId);
    List<LabTrainingRun> findByExperimentIdOrderByCreatedAtDesc(String experimentId);
    List<LabTrainingRun> findByStatusOrderByCreatedAtAsc(String status);
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select r from LabTrainingRun r where r.id=:id")
    Optional<LabTrainingRun> lock(@Param("id") String id);
}
