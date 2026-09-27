package com.aisocialgame.model;

import com.aisocialgame.model.converter.MapToJsonConverter;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Entity
@Table(name = "ai_turn_jobs", indexes = @Index(name = "idx_ai_turn_job_status", columnList = "status,created_at"))
public class AiTurnJob {
    @Id @Column(length = 64) private String id;
    @Column(nullable = false, length = 64) private String roomId;
    @Column(nullable = false, length = 96) private String instanceId;
    @Column(nullable = false, length = 64) private String actorId;
    @Column(nullable = false, length = 40) private String kind;
    @Column(nullable = false, length = 256) private String turnKey;
    @Column(nullable = false, length = 24) private String status = "QUEUED";
    @Convert(converter = MapToJsonConverter.class) @Column(columnDefinition = "LONGTEXT")
    private Map<String, Object> observation = new LinkedHashMap<>();
    @Convert(converter = MapToJsonConverter.class) @Column(columnDefinition = "LONGTEXT")
    private Map<String, Object> diagnostics = new LinkedHashMap<>();
    public Map<String, Object> getDiagnostics() { if (diagnostics == null) diagnostics = new LinkedHashMap<>(); return diagnostics; }
    public void setDiagnostics(Map<String, Object> value) { diagnostics = new LinkedHashMap<>(value); }
    private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    @Version private Long version;
    public String getId() { return id; }
    public String getRoomId() { return roomId; }
    public String getInstanceId() { return instanceId; }
    public String getActorId() { return actorId; }
    public String getKind() { return kind; }
    public String getTurnKey() { return turnKey; }
    public String getStatus() { return status; }
    public Map<String, Object> getObservation() { return observation; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setId(String id) { this.id = id; }
    public void setRoomId(String roomId) { this.roomId = roomId; }
    public void setInstanceId(String instanceId) { this.instanceId = instanceId; }
    public void setActorId(String actorId) { this.actorId = actorId; }
    public void setKind(String kind) { this.kind = kind; }
    public void setTurnKey(String turnKey) { this.turnKey = turnKey; }
    public void setStatus(String status) { this.status = status; }
    public void setObservation(Map<String, Object> observation) { this.observation = observation; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }
}
