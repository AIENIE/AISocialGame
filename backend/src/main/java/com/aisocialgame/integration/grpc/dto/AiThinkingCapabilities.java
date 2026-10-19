package com.aisocialgame.integration.grpc.dto;

import java.util.List;

public record AiThinkingCapabilities(boolean declared, boolean supportsEnable, boolean supportsDisable,
                                     List<Option> options, Boolean defaultEnabled, String defaultEffort) {
    public AiThinkingCapabilities { options = List.copyOf(options); }
    public record Option(String effort, String label) { }
}
