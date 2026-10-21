package com.aisocialgame.lab;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface LabEvaluationRepository extends JpaRepository<LabEvaluation, String> {
    List<LabEvaluation> findByExperimentIdOrderByCreatedAtDesc(String experimentId);
}
