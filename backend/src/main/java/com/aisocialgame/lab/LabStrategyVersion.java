package com.aisocialgame.lab;

import com.aisocialgame.model.converter.MapToJsonConverter;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.*;

/** An immutable shared strategy, never represented as a fictitious participant. */
@Entity
@org.hibernate.annotations.Immutable
@Table(name="lab_strategy_versions", uniqueConstraints={
        @UniqueConstraint(name="uk_lab_strategy_sequence",columnNames={"experiment_id","sequence_number"}),
        @UniqueConstraint(name="uk_lab_strategy_request",columnNames={"request_id"})})
public class LabStrategyVersion {
    @Id @Column(length=36) public String id=UUID.randomUUID().toString();
    @Column(name="experiment_id",nullable=false,length=36) public String experimentId;
    @Column(name="sequence_number",nullable=false) public int sequenceNumber;
    @Column(name="previous_id",length=36) public String previousId;
    @Column(name="game_id",nullable=false,length=64) public String gameId="werewolf";
    @Column(name="request_id",nullable=false,length=128) public String requestId;
    @Convert(converter=MapToJsonConverter.class) @Column(nullable=false,columnDefinition="LONGTEXT")
    public Map<String,Object> content=new LinkedHashMap<>();
    @Convert(converter=MapToJsonConverter.class) @Column(nullable=false,columnDefinition="LONGTEXT")
    public Map<String,Object> compatibility=new LinkedHashMap<>();
    @Convert(converter=MapToJsonConverter.class) @Column(nullable=false,columnDefinition="LONGTEXT")
    public Map<String,Object> evidence=new LinkedHashMap<>();
    @Column(name="content_hash",nullable=false,length=64) public String contentHash;
    @Column(name="created_at",nullable=false) public LocalDateTime createdAt=LocalDateTime.now();
}
