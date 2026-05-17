package com.aisocialgame.engine;

public record PhaseDefinition(String phase, String displayName, int defaultDurationSeconds, boolean allowChat) {
}
