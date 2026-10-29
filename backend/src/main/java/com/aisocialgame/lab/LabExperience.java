package com.aisocialgame.lab;

import com.aisocialgame.model.converter.MapToJsonConverter;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.*;

@Entity
@Table(name = "lab_experiences", uniqueConstraints = @UniqueConstraint(name = "uk_lab_participant_version", columnNames = {"experiment_id", "participant_id", "sequence_number"}))
public class LabExperience {
    @Id @Column(length = 36) public String id = UUID.randomUUID().toString();
    @Column(name = "experiment_id", nullable = false, length = 36) public String experimentId;
    @Column(name = "agent_id", nullable = false, length = 36) public String agentId;
    @Column(name = "participant_id", nullable = false, length = 36) public String participantId;
    @Column(name = "sequence_number", nullable = false) public int sequenceNumber;
    @Column(nullable = false, length = 1200) public String summary;
    @Column(length = 36) public String previousId;
    @Convert(converter = MapToJsonConverter.class)
    @Column(nullable = false, columnDefinition = "LONGTEXT") public Map<String, Object> evidence = new LinkedHashMap<>();
    @Column(nullable = false) public LocalDateTime createdAt = LocalDateTime.now();
}
