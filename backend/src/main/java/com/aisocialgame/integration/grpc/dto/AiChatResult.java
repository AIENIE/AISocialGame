package com.aisocialgame.integration.grpc.dto;

public record AiChatResult(String content, String modelKey, long promptTokens, long completionTokens,
                           String finishReason, Long reasoningTokens, String budgetId, String settlementState) {
    public AiChatResult(String content, String modelKey, long promptTokens, long completionTokens) {
        this(content, modelKey, promptTokens, completionTokens, null, null, null, null);
    }
}
