package com.aisocialgame.integration.grpc.dto;

public record AiModelOptionDto(
        long id,
        String displayName,
        String provider,
        double inputRate,
        double outputRate,
        String type,
        boolean supportsImageInput,
        String modelKey,
        boolean supportsStreaming,
        AiThinkingCapabilities thinking
) {
    public AiModelOptionDto(long id, String displayName, String provider, double inputRate, double outputRate,
                            String type, boolean supportsImageInput) {
        this(id, displayName, provider, inputRate, outputRate, type, supportsImageInput, null, false, null);
    }

    public static AiModelOptionDto from(fireflychat.ai.v1.ModelOption model) {
        var thinking = model.hasThinking() ? model.getThinking() : null;
        return new AiModelOptionDto(model.getId(), model.getDisplayName(), model.getProvider(), model.getInputRate(),
                model.getOutputRate(), model.getType().name(), model.getSupportsImageInput(), model.getModelKey(),
                model.getSupportsStreaming(), thinking == null ? null : new AiThinkingCapabilities(
                thinking.getDeclared(), thinking.getSupportsEnable(), thinking.getSupportsDisable(),
                thinking.getOptionsList().stream().map(option -> new AiThinkingCapabilities.Option(
                        effortName(option.getEffort()), option.getLabel())).toList(),
                thinking.hasDefaultEnabled() ? thinking.getDefaultEnabled() : null,
                thinking.hasDefaultEffort() ? effortName(thinking.getDefaultEffort()) : null));
    }

    private static String effortName(fireflychat.ai.v1.ThinkingEffort effort) {
        return effort.name().replace("THINKING_EFFORT_", "");
    }
}
