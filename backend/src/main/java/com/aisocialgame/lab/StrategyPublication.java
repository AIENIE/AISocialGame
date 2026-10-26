package com.aisocialgame.lab;

import com.aisocialgame.model.converter.MapToJsonConverter;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.*;

/** Durable operation receipt. Later binding changes must never change an earlier receipt. */
@Entity
@org.hibernate.annotations.Immutable
@Table(name="strategy_publications",uniqueConstraints=@UniqueConstraint(name="uk_strategy_publication_operation",columnNames="operation_id"))
public class StrategyPublication {
    @Id @Column(length=36) public String id=UUID.randomUUID().toString();
    @Column(name="operation_id",nullable=false,length=36) public String operationId;
    @Column(name="request_hash",nullable=false,length=64) public String requestHash;
    @Column(nullable=false,length=16) public String action;
    @Column(name="game_id",nullable=false,length=64) public String gameId="werewolf";
    @Column(name="strategy_version_id",length=36) public String strategyVersionId;
    @Column(nullable=false,length=16) public String mode="TRIAL";
    @Column(name="created_by",nullable=false,length=128) public String operator;
    @Convert(converter=MapToJsonConverter.class) @Column(nullable=false,columnDefinition="LONGTEXT")
    public Map<String,Object> parameters=new LinkedHashMap<>();
    @Convert(converter=MapToJsonConverter.class) @Column(name="previous_bindings",nullable=false,columnDefinition="LONGTEXT")
    public Map<String,Object> previousBindings=new LinkedHashMap<>();
    @Convert(converter=MapToJsonConverter.class) @Column(nullable=false,columnDefinition="LONGTEXT")
    public Map<String,Object> result=new LinkedHashMap<>();
    @Column(name="created_at",nullable=false) public LocalDateTime createdAt=LocalDateTime.now();
}
