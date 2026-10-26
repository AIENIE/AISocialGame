package com.aisocialgame.lab;

import com.aisocialgame.model.converter.MapToJsonConverter;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.*;

@Entity
@org.hibernate.annotations.Immutable
@Table(name="lab_training_cases",indexes=@Index(name="idx_lab_training_case_run",columnList="run_id,purpose,ordinal"))
public class LabTrainingCase {
    @Id @Column(length=36) public String id=UUID.randomUUID().toString();
    @Column(name="run_id",nullable=false,length=36) public String runId;
    @Column(nullable=false,length=24) public String purpose;
    @Column(name="source_type",nullable=false,length=48) public String sourceType;
    @Column(name="source_group",nullable=false,length=200) public String sourceGroup;
    @Column(name="persona_id",nullable=false,length=64) public String personaId;
    @Column(name="participant_id",nullable=false,length=36) public String participantId;
    @Column(nullable=false) public int ordinal;
    @Column(name="content_hash",nullable=false,length=64) public String contentHash;
    @Convert(converter=MapToJsonConverter.class) @Column(nullable=false,columnDefinition="LONGTEXT")
    public Map<String,Object> data=new LinkedHashMap<>();
    @Column(name="created_at",nullable=false) public LocalDateTime createdAt=LocalDateTime.now();
}
