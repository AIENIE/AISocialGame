package com.aisocialgame.service.ai.config;

import fireflychat.ai.v1.ChatCompletionsRequest;
import fireflychat.ai.v1.ThinkingEffort;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable for the lifetime of a logical turn, including budget waits and repairs. */
public record AiModelSelection(AiFunctionCode functionCode, String modelKey, ThinkingMode thinkingMode,
                               String thinkingEffort, long configVersion) {
    /** No persisted function config existed when this turn started. */
    public static final long ENVIRONMENT_VERSION = -1;
    public enum ThinkingMode { DEFAULT, ENABLED, DISABLED }
    public AiModelSelection {
        modelKey = modelKey == null ? "" : modelKey;
        java.util.Objects.requireNonNull(thinkingMode);
    }
    public static AiModelSelection defaults(String modelKey) {
        return environmentDefault(null, modelKey);
    }
    public static AiModelSelection environmentDefault(AiFunctionCode functionCode, String modelKey) {
        return new AiModelSelection(functionCode, modelKey, ThinkingMode.DEFAULT, null, ENVIRONMENT_VERSION);
    }
    public void apply(ChatCompletionsRequest.Builder request) {
        request.setModel(modelKey).clearThinkingEnabled().clearThinkingEffort();
        if (thinkingMode != ThinkingMode.DEFAULT) request.setThinkingEnabled(thinkingMode == ThinkingMode.ENABLED);
        if (thinkingMode == ThinkingMode.ENABLED && thinkingEffort != null)
            request.setThinkingEffort(ThinkingEffort.valueOf("THINKING_EFFORT_" + thinkingEffort));
    }
    public Map<String, Object> toMap() {
        var result = new LinkedHashMap<String, Object>();
        if (functionCode != null) result.put("functionCode", functionCode.name());
        result.put("modelKey", modelKey); result.put("thinkingMode", thinkingMode.name());
        if (thinkingEffort != null) result.put("thinkingEffort", thinkingEffort);
        result.put("configVersion", configVersion);
        return result;
    }
    public static AiModelSelection fromMap(Map<String, Object> value) {
        return new AiModelSelection(value.containsKey("functionCode") ? AiFunctionCode.valueOf((String) value.get("functionCode")) : null,
                (String) value.get("modelKey"), ThinkingMode.valueOf((String) value.get("thinkingMode")),
                (String) value.get("thinkingEffort"), ((Number) value.get("configVersion")).longValue());
    }
}
