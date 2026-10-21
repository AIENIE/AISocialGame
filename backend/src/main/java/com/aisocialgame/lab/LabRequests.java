package com.aisocialgame.lab;

import com.aisocialgame.service.ai.config.AiModelSelection;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;

public final class LabRequests {
    private LabRequests() {}
    public record Agent(@NotBlank @Size(max=64) String name, @NotBlank String personaId,
                        @NotNull @Valid Selection selection) {}
    public record Selection(@NotBlank @Size(max=128) String modelKey, @NotNull AiModelSelection.ThinkingMode thinkingMode,
                            String thinkingEffort) {
        public AiModelSelection snapshot() { return new AiModelSelection(null, modelKey, thinkingMode, thinkingEffort, 0); }
    }
    public record Experiment(@NotBlank @Size(max=128) String name,
                             @NotNull @Size(min=4,max=10) List<String> agentIds,
                             @Min(4) @Max(10) int playerCount, @Min(1) @Max(10000) int targetGames,
                             @Min(1) @Max(3) int concurrency, @NotBlank String wordPack, boolean hasBlank,
                             @Min(1) long budgetPoints, @NotNull @Valid Selection coach,
                             Map<String,String> initialExperienceIds) {}
    public record Evaluation(@NotBlank String agentId, @Min(1) @Max(1000) int pairs,
                             @NotBlank String candidateExperienceId) {}
}
