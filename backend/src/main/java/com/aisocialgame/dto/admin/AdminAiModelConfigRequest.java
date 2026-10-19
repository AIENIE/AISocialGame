package com.aisocialgame.dto.admin;

import com.aisocialgame.service.ai.config.AiModelSelection.ThinkingMode;
import jakarta.validation.constraints.*;

public record AdminAiModelConfigRequest(@NotBlank @Size(max = 128) String modelKey, @NotNull ThinkingMode thinkingMode,
                                        @Pattern(regexp = "MINIMAL|LOW|MEDIUM|HIGH|XHIGH|MAX") String thinkingEffort,
                                        @NotNull @PositiveOrZero Long catalogVersion, @NotNull @PositiveOrZero Long version) { }
