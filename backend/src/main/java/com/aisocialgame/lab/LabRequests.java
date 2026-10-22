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
    public record Participant(@NotBlank String agentId, String initialExperienceId, @Valid Selection selection) {
        public Participant(String agentId,String initialExperienceId) { this(agentId,initialExperienceId,null); }
    }
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties("concurrency")
    public record Experiment(@NotBlank @Size(max=128) String name,
                             @Size(min=4,max=10) List<@NotBlank String> agentIds,
                             @Min(4) @Max(10) int playerCount, @Min(1) @Max(10000) int targetGames,
                             @NotBlank String wordPack, boolean hasBlank,
                             @Min(1) long budgetPoints, @NotNull @Valid Selection coach,
                             Map<String,String> initialExperienceIds,
                             @Size(min=4,max=10) List<@NotNull @Valid Participant> participants) {}
    public record Evaluation(String participantId, String agentId, @Min(1) @Max(1000) int pairs,
                             @NotBlank String candidateExperienceId) {}
    public record Budget(@Min(1) long budgetPoints) {
        @com.fasterxml.jackson.annotation.JsonCreator(mode=com.fasterxml.jackson.annotation.JsonCreator.Mode.DELEGATING)
        public static Budget from(com.fasterxml.jackson.databind.JsonNode body) {
            var points=body==null?null:body.get("budgetPoints");
            if(points==null || !points.isIntegralNumber() || !points.canConvertToLong())
                throw new IllegalArgumentException("budgetPoints 必须为正整数");
            return new Budget(points.longValue());
        }
    }
}
