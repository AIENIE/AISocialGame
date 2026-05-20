package com.aisocialgame.dto.admin;

import jakarta.validation.constraints.NotBlank;

import java.time.LocalDateTime;

public class AdminSafetyControlRequest {
    @NotBlank
    private String scope;
    @NotBlank
    private String targetKey;
    private String action;
    private String reason;
    private LocalDateTime expiresAt;

    public String getScope() { return scope; }
    public String getTargetKey() { return targetKey; }
    public String getAction() { return action; }
    public String getReason() { return reason; }
    public LocalDateTime getExpiresAt() { return expiresAt; }

    public void setScope(String scope) { this.scope = scope; }
    public void setTargetKey(String targetKey) { this.targetKey = targetKey; }
    public void setAction(String action) { this.action = action; }
    public void setReason(String reason) { this.reason = reason; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
}
