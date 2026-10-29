package com.aisocialgame.lab;

import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface LabExperienceRepository extends JpaRepository<LabExperience, String> {
    Page<LabExperience> findByExperimentIdOrderByCreatedAtDesc(String experimentId, Pageable page);
    @Query("select coalesce(max(e.sequenceNumber),0) from LabExperience e where e.experimentId=:experimentId and e.participantId=:participantId")
    int latestSequence(@Param("experimentId") String experimentId, @Param("participantId") String participantId);
}
