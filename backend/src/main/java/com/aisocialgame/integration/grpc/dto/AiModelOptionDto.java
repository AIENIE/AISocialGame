package com.aisocialgame.integration.grpc.dto;

public record AiModelOptionDto(
        long id,
        String displayName,
        String provider,
        double inputRate,
        double outputRate,
        String type,
        boolean supportsImageInput
) {
}
