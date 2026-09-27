package com.aisocialgame.model;

import jakarta.persistence.*;

/** Local attempt accounting. Never contains prompts, responses, credentials or exception messages. */
@Entity
@Table(name="ai_call_usage", indexes={@Index(name="idx_call_usage_time", columnList="startedEpochMs")})
public class AiCallUsage {
    @Id @Column(length=128) public String id;
    @Column(length=64) public String source;
    @Column(length=64) public String userId;
    @Column(length=64) public String roomId;
    @Column(length=64) public String personaId;
    @Column(length=128) public String modelKey;
    public long startedEpochMs;
    public Long completedEpochMs;
    @Column(length=32) public String outcome;
    public boolean admitted;
    public Long promptTokens;
    public Long completionTokens;
    public boolean anomaly;
    @Column(length=128) public String anomalyScopes;
    public AiCallUsage() {}
}
