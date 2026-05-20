package com.aisocialgame.dto.admin;

import com.aisocialgame.model.AiSafetyControl;

import java.time.LocalDateTime;

public class AdminSafetyControlView {
    private final Long id;
    private final String scope;
    private final String targetKey;
    private final String action;
    private final String reason;
    private final String createdBy;
    private final boolean active;
    private final LocalDateTime expiresAt;
    private final LocalDateTime createdAt;

    public AdminSafetyControlView(AiSafetyControl control) {
        this.id = control.getId();
        this.scope = control.getScope();
        this.targetKey = control.getTargetKey();
        this.action = control.getAction();
        this.reason = control.getReason();
        this.createdBy = control.getCreatedBy();
        this.active = control.isActive();
        this.expiresAt = control.getExpiresAt();
        this.createdAt = control.getCreatedAt();
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
}
