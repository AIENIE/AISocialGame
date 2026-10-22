package com.aisocialgame.lab;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;

public interface LabExperimentRepository extends JpaRepository<LabExperiment, String> {
    Page<LabExperiment> findAllByOrderByCreatedAtDesc(Pageable page);
    List<LabExperiment> findTop32ByStatusInOrderByCreatedAtAsc(Collection<String> statuses);
    List<LabExperiment> findByStatus(String status);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from LabExperiment e where e.id=:id")
    Optional<LabExperiment> lock(@Param("id") String id);
}
