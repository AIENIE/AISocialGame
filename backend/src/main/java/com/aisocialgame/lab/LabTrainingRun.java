package com.aisocialgame.lab;

import com.aisocialgame.model.converter.MapToJsonConverter;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.*;

@Entity
@Table(name="lab_training_runs",uniqueConstraints=@UniqueConstraint(name="uk_lab_training_operation",columnNames={"experiment_id","operation_id"}))
public class LabTrainingRun {
    @Id @Column(length=36) public String id=UUID.randomUUID().toString();
    @Column(name="experiment_id",nullable=false,length=36) public String experimentId;
    @Column(name="operation_id",nullable=false,length=64) public String operationId;
    @Column(name="request_hash",nullable=false,length=64) public String requestHash;
    @Column(nullable=false,length=32) public String goal="RULES_EVIDENCE";
    @Column(nullable=false,length=24) public String status="DRAFT";
    @Column(nullable=false,length=24) public String stage="PRACTICE";
    @Column(length=128) public String reason;
    @Column(name="candidate_strategy_version_id",length=36) public String candidateStrategyVersionId;
    @Convert(converter=MapToJsonConverter.class) @Column(nullable=false,columnDefinition="LONGTEXT")
    public Map<String,Object> configuration=new LinkedHashMap<>();
    @Convert(converter=MapToJsonConverter.class) @Column(nullable=false,columnDefinition="LONGTEXT")
    public Map<String,Object> assessment=new LinkedHashMap<>();
    @Column(name="created_at",nullable=false) public LocalDateTime createdAt=LocalDateTime.now();
    @Column(name="updated_at_epoch_ms",nullable=false) public long updatedAtEpochMs=System.currentTimeMillis();
    @Version public Long version;
}
