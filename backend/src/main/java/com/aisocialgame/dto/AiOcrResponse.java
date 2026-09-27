package com.aisocialgame.dto;

import com.aisocialgame.integration.grpc.dto.AiOcrResult;

public class AiOcrResponse {
    private final String requestId;
    private final String modelKey;
    private final String outputType;
    private final String content;
    private final String rawJson;
    private final long promptTokens;
    private final long completionTokens;
    private final long totalTokens;

    public AiOcrResponse(AiOcrResult result) {
        this.requestId = result.requestId();
        this.modelKey = result.modelKey();
        this.outputType = result.outputType();
        this.content = result.content();
        this.rawJson = result.rawJson();
        this.promptTokens = result.promptTokens();
        this.completionTokens = result.completionTokens();
        this.totalTokens = result.totalTokens();
    }

    public String getRequestId() {
        return requestId;
    }

    public String getModelKey() {
        return modelKey;
    }

    public String getOutputType() {
        return outputType;
    }

    public String getContent() {
        return content;
    }

    public String getRawJson() {
        return rawJson;
    }
    public long getPromptTokens() { return promptTokens; }
    public long getCompletionTokens() { return completionTokens; }
    public long getTotalTokens() { return totalTokens; }
}
