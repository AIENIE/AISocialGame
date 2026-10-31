package com.aisocialgame.integration.grpc.dto;

import java.time.Instant;

public record UsageRecordSnapshot(
        long id,
        String requestId,
        String projectKey,
        String modelKey,
        long promptTokens,
        long completionTokens,
        long billedPoints,
        Instant createdAt
) {
}
