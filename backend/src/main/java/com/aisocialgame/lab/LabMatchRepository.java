package com.aisocialgame.lab;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;

public interface LabMatchRepository extends JpaRepository<LabMatch, String> {
    Page<LabMatch> findByExperimentIdOrderBySequenceNumberDesc(String experimentId, Pageable page);
    List<LabMatch> findByExperimentIdAndStatusInOrderBySequenceNumberAsc(String experimentId, Collection<String> statuses);
    List<LabMatch> findByEvaluationIdOrderBySequenceNumberAsc(String evaluationId);
    Optional<LabMatch> findByExperimentIdAndSequenceNumber(String experimentId, int sequenceNumber);
    Optional<LabMatch> findByRoomId(String roomId);
    long countByStatusIn(Collection<String> statuses);
    List<LabMatch> findTop3ByStatusInOrderByCreatedAtAsc(Collection<String> statuses);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from LabMatch m where m.id=:id")
    Optional<LabMatch> lock(@Param("id") String id);
}
