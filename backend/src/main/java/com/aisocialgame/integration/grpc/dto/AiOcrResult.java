package com.aisocialgame.integration.grpc.dto;

public record AiOcrResult(
        String requestId,
        String modelKey,
        String outputType,
        String content,
        String rawJson,
        long promptTokens,
        long completionTokens,
        long totalTokens
) {
    public AiOcrResult(String requestId, String modelKey, String outputType, String content, String rawJson) {
        this(requestId, modelKey, outputType, content, rawJson, 0, 0, 0);
    }
}
