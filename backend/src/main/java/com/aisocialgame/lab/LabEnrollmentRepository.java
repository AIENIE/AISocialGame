package com.aisocialgame.lab;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface LabEnrollmentRepository extends JpaRepository<LabEnrollment, String> {
    Optional<LabEnrollment> findByExperimentIdAndUserId(String experimentId, String userId);
    List<LabEnrollment> findByExperimentIdAndStatusOrderByJoinedAtAsc(String experimentId, String status);
    List<LabEnrollment> findByMatchIdOrderByJoinedAtAscIdAsc(String matchId);
}
