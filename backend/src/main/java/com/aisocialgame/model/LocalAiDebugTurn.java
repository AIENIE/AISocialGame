package com.aisocialgame.model;

import com.aisocialgame.model.converter.MapToJsonConverter;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/** One durable turn; each attempt occupies its own numbered slot in the private payload. */
@Entity
@Table(name = "local_ai_debug_turns", indexes = @Index(name = "idx_local_ai_debug_instance", columnList = "room_id,instance_id,created_at,id"))
public class LocalAiDebugTurn {
    @Id @Column(length = 64) public String id;
    @Column(nullable = false, length = 64) public String roomId;
    @Column(nullable = false, length = 64) public String gameId;
    @Column(nullable = false, length = 96) public String instanceId;
    @Column(nullable = false, length = 64) public String actorId;
    @Column(length = 128) public String actorName;
    public Integer seatNumber;
    @Column(length = 64) public String roleKey;
    @Column(length = 64) public String phase;
    public int roundNumber;
    @Column(length = 40) public String kind;
    @Column(length = 24) public String status;
    @Column(length = 128) public String terminalReason;
    @Column(length = 128) public String modelKey;
    public Long promptTokens;
    public Long completionTokens;
    public Long cacheReadTokens;
    public Long cacheWriteTokens;
    public Long reasoningTokens;
    public Boolean usageAuthoritative;
    public boolean usageComplete;
    public int attemptCount;
    public int responseCount;
    public Long latencyMs;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
    @Convert(converter = MapToJsonConverter.class) @Column(columnDefinition = "LONGTEXT", nullable = false)
    public Map<String, Object> payload = new LinkedHashMap<>();
    @Version public Long version;
}
