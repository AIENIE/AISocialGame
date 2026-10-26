package com.aisocialgame.lab;

import com.aisocialgame.model.converter.MapToJsonConverter;
import jakarta.persistence.*;
import java.util.*;

@Entity
@Table(name="lab_training_tasks",uniqueConstraints=@UniqueConstraint(name="uk_lab_training_task_identity",columnNames={"run_id","task_key"}),
    indexes=@Index(name="idx_lab_training_task_run",columnList="run_id,ordinal"))
public class LabTrainingTask {
    @Id @Column(length=36) public String id=UUID.randomUUID().toString();
    @Column(name="run_id",nullable=false,length=36) public String runId;
    @Column(name="case_id",length=36) public String caseId;
    @Column(name="task_key",nullable=false,length=128) public String taskKey;
    @Column(nullable=false,length=24) public String kind;
    @Column(length=24) public String variant;
    @Column(nullable=false,length=24) public String status="PENDING";
    @Column(nullable=false) public int ordinal;
    @Column(length=128) public String reason;
    @Column(name="request_id",length=128) public String requestId;
    @Column(nullable=false) public int attempt;
    @Column(name="paid_retries",nullable=false) public int paidRetries;
    @Column(name="started_at_epoch_ms") public Long startedAtEpochMs;
    @Column(name="updated_at_epoch_ms",nullable=false) public long updatedAtEpochMs=System.currentTimeMillis();
    @Column(name="not_before_epoch_ms",nullable=false) public long notBeforeEpochMs;
    @Column(name="lease_until_epoch_ms",nullable=false) public long leaseUntilEpochMs;
    @Convert(converter=MapToJsonConverter.class) @Column(nullable=false,columnDefinition="LONGTEXT")
    public Map<String,Object> input=new LinkedHashMap<>();
    @Convert(converter=MapToJsonConverter.class) @Column(nullable=false,columnDefinition="LONGTEXT")
    public Map<String,Object> checkpoint=new LinkedHashMap<>();
    @Convert(converter=MapToJsonConverter.class) @Column(nullable=false,columnDefinition="LONGTEXT")
    public Map<String,Object> result=new LinkedHashMap<>();
    @Version public Long version;
}
