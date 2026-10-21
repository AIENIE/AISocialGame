package com.aisocialgame.lab;

import com.aisocialgame.model.converter.MapToJsonConverter;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.*;

@Entity
@Table(name = "lab_evaluations")
public class LabEvaluation {
    @Id @Column(length = 36) public String id = UUID.randomUUID().toString();
    @Column(nullable = false, length = 36) public String experimentId;
    @Column(nullable = false, length = 36) public String agentId;
    @Column(name = "participant_id", nullable = false, length = 36) public String participantId;
    @Column(nullable = false) public int pairs;
    @Column(nullable = false) public int nextGame;
    @Column(nullable = false) public long randomSeed;
    @Column(nullable = false, length = 24) public String status = "RUNNING";
    @Convert(converter = MapToJsonConverter.class)
    @Column(nullable = false, columnDefinition = "LONGTEXT") public Map<String, Object> configuration = new LinkedHashMap<>();
    @Column(nullable = false) public LocalDateTime createdAt = LocalDateTime.now();
    @Version public Long version;
}
