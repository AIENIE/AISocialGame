package com.aisocialgame.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "ai_safety_controls")
public class AiSafetyControl {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String scope;

    @Column(nullable = false, length = 128)
    private String targetKey;

    @Column(nullable = false, length = 32)
    private String action;

    @Column(length = 255)
    private String reason;

    @Column(length = 64)
    private String createdBy;

    private boolean active = true;
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @PrePersist
    public void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    public Long getId() { return id; }
    public String getScope() { return scope; }
    public String getTargetKey() { return targetKey; }
    public String getAction() { return action; }
    public String getReason() { return reason; }
    public String getCreatedBy() { return createdBy; }
    public boolean isActive() { return active; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    public void setId(Long id) { this.id = id; }
    public void setScope(String scope) { this.scope = scope; }
    public void setTargetKey(String targetKey) { this.targetKey = targetKey; }
    public void setAction(String action) { this.action = action; }
    public void setReason(String reason) { this.reason = reason; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public void setActive(boolean active) { this.active = active; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
