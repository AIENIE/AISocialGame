package com.aisocialgame.lab;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface LabTrainingCaseRepository extends JpaRepository<LabTrainingCase,String> {
    List<LabTrainingCase> findByRunIdOrderByOrdinalAsc(String runId);
}
