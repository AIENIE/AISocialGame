package com.aisocialgame.dto;

import com.aisocialgame.integration.grpc.dto.UsageRecordSnapshot;

import java.time.format.DateTimeFormatter;

public class UsageRecordView {
    private final String requestId;
    private final String modelKey;
    private final long promptTokens;
    private final long completionTokens;
    private final long billedPoints;
    private final String createdAt;

    public UsageRecordView(UsageRecordSnapshot snapshot) {
        this.requestId = snapshot.requestId();
        this.modelKey = snapshot.modelKey();
        this.promptTokens = snapshot.promptTokens();
        this.completionTokens = snapshot.completionTokens();
        this.billedPoints = snapshot.billedPoints();
        this.createdAt = snapshot.createdAt() == null ? null : DateTimeFormatter.ISO_INSTANT.format(snapshot.createdAt());
    }

    public String getRequestId() {
        return requestId;
    }

    public String getModelKey() {
        return modelKey;
    }

    public long getPromptTokens() {
        return promptTokens;
    }

    public long getCompletionTokens() {
        return completionTokens;
    }

    public long getBilledPoints() {
        return billedPoints;
    }

    public String getCreatedAt() {
        return createdAt;
    }
}
