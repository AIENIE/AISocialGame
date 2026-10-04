package com.aisocialgame.model.credit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "credit_ledger_entries")
public class CreditLedgerEntry {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "request_id", nullable = false, unique = true, length = 128)
    private String requestId;

    @Column(name = "user_id", nullable = false)
    private long userId;

    @Column(name = "project_key", nullable = false, length = 64)
    private String projectKey;

    @Column(nullable = false, length = 32)
    private String type;

    @Column(name = "token_delta_temp", nullable = false)
    private long tokenDeltaTemp;

    @Column(name = "token_delta_permanent", nullable = false)
    private long tokenDeltaPermanent;

    @Column(name = "token_delta_public", nullable = false)
    private long tokenDeltaPublic;

    @Column(name = "balance_temp", nullable = false)
    private long balanceTemp;

    @Column(name = "balance_permanent", nullable = false)
    private long balancePermanent;

    @Column(name = "balance_public", nullable = false)
    private long balancePublic;

    @Column(length = 64)
    private String source;

    @Column(name = "metadata_json", columnDefinition = "LONGTEXT")
    private String metadataJson;

    @Column(name = "related_entry_id")
    private Long relatedEntryId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    public void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
    }

    public Long getId() {
        return id;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public long getUserId() {
        return userId;
    }

    public void setUserId(long userId) {
        this.userId = userId;
    }

    public String getProjectKey() {
        return projectKey;
    }

    public void setProjectKey(String projectKey) {
        this.projectKey = projectKey;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public long getTokenDeltaTemp() {
        return tokenDeltaTemp;
    }

    public void setTokenDeltaTemp(long tokenDeltaTemp) {
        this.tokenDeltaTemp = tokenDeltaTemp;
    }

    public long getTokenDeltaPermanent() {
        return tokenDeltaPermanent;
    }

    public void setTokenDeltaPermanent(long tokenDeltaPermanent) {
        this.tokenDeltaPermanent = tokenDeltaPermanent;
    }

    public long getTokenDeltaPublic() {
        return tokenDeltaPublic;
    }

    public void setTokenDeltaPublic(long tokenDeltaPublic) {
        this.tokenDeltaPublic = tokenDeltaPublic;
    }

    public long getBalanceTemp() {
        return balanceTemp;
    }

    public void setBalanceTemp(long balanceTemp) {
        this.balanceTemp = balanceTemp;
    }

    public long getBalancePermanent() {
        return balancePermanent;
    }

    public void setBalancePermanent(long balancePermanent) {
        this.balancePermanent = balancePermanent;
    }

    public long getBalancePublic() {
        return balancePublic;
    }

    public void setBalancePublic(long balancePublic) {
        this.balancePublic = balancePublic;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getMetadataJson() {
        return metadataJson;
    }

    public void setMetadataJson(String metadataJson) {
        this.metadataJson = metadataJson;
    }

    public Long getRelatedEntryId() {
        return relatedEntryId;
    }

    public void setRelatedEntryId(Long relatedEntryId) {
        this.relatedEntryId = relatedEntryId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
