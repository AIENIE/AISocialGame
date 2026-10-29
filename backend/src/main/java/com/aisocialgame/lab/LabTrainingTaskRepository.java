package com.aisocialgame.lab;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.*;
public interface LabTrainingTaskRepository extends JpaRepository<LabTrainingTask,String> {
    List<LabTrainingTask> findByRunIdOrderByOrdinalAsc(String runId);
    Optional<LabTrainingTask> findByRunIdAndTaskKey(String runId,String taskKey);
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select t from LabTrainingTask t where t.id=:id")
    Optional<LabTrainingTask> lock(@Param("id") String id);
}
