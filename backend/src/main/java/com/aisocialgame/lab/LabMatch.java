package com.aisocialgame.lab;

import com.aisocialgame.model.converter.MapToJsonConverter;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.*;

@Entity
@Table(name = "lab_matches", uniqueConstraints = @UniqueConstraint(name = "uk_lab_match_sequence", columnNames = {"experiment_id", "sequence_number"}))
public class LabMatch {
    @Id @Column(length = 36) public String id = UUID.randomUUID().toString();
    @Column(name = "experiment_id", nullable = false, length = 36) public String experimentId;
    @Column(name = "sequence_number", nullable = false) public int sequenceNumber;
    @Column(nullable = false, length = 36, unique = true) public String roomId;
    @Column(length = 96) public String archiveId;
    @Column(length = 36) public String evaluationId;
    @Column(length = 16) public String variant;
    @Column(nullable = false, length = 32) public String status = "WAITING";
    @Column(length = 128) public String reason;
    @Column(nullable = false) public long randomSeed;
    @Column(nullable = false) public long startsAtEpochMs;
    @Column public Long reviewLeaseUntil;
    @Convert(converter = MapToJsonConverter.class)
    @Column(nullable = false, columnDefinition = "LONGTEXT") public Map<String, Object> snapshot = new LinkedHashMap<>();
    @Column(nullable = false) public LocalDateTime createdAt = LocalDateTime.now();
    public LocalDateTime finishedAt;
    @Version public Long version;
}
