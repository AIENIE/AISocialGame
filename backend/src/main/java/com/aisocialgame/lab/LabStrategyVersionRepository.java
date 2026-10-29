package com.aisocialgame.lab;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;

public interface LabStrategyVersionRepository extends JpaRepository<LabStrategyVersion,String> {
    List<LabStrategyVersion> findByExperimentIdOrderBySequenceNumberDesc(String experimentId);
    Optional<LabStrategyVersion> findByRequestId(String requestId);
    @Query("select coalesce(max(s.sequenceNumber),0) from LabStrategyVersion s where s.experimentId=:experimentId")
    int latestSequence(@Param("experimentId") String experimentId);
}
