package com.aisocialgame.lab;

import com.aisocialgame.model.converter.MapToJsonConverter;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.*;

@Entity
@Table(name = "lab_experiments")
public class LabExperiment {
    @Id @Column(length = 36) public String id = UUID.randomUUID().toString();
    @Column(nullable = false, length = 128) public String name;
    @Column(nullable = false, length = 32) public String status = "DRAFT";
    @Column(length = 128) public String reason;
    @Column(nullable = false) public long budgetPoints;
    @Column(nullable = false) public int nextSequence = 1;
    @Column(nullable = false) public int reviewCursor = 1;
    @Convert(converter = MapToJsonConverter.class)
    @Column(nullable = false, columnDefinition = "LONGTEXT") public Map<String, Object> configuration = new LinkedHashMap<>();
    @Column(nullable = false, length = 128) public String createdBy;
    @Column(nullable = false) public LocalDateTime createdAt = LocalDateTime.now();
    @Version public Long version;
}
