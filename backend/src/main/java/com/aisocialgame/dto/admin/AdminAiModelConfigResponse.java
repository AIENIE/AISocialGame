package com.aisocialgame.dto.admin;

import com.aisocialgame.integration.grpc.dto.AiModelOptionDto;
import com.aisocialgame.service.ai.config.AiFunctionCode;
import com.aisocialgame.service.ai.config.AiModelSelection.ThinkingMode;
import java.time.LocalDateTime;
import java.util.List;

public record AdminAiModelConfigResponse(long catalogVersion, LocalDateTime syncedAt, List<AiModelOptionDto> models,
                                         List<FunctionView> functions) {
    public record FunctionView(AiFunctionCode functionCode, String label, String modelKey, ThinkingMode thinkingMode,
                               String thinkingEffort, long version, String status, String statusMessage,
                               boolean inferenceEnabled, String inferenceMessage) { }
}
