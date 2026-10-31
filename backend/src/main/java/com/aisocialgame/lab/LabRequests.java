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
    public record Participant(@NotBlank String agentId, String initialExperienceId, @Valid Selection selection) {}
    public record Experiment(@NotBlank @Size(max=128) String name,
                             @Min(4) @Max(10) int playerCount, @Min(1) @Max(10000) int targetGames,
                             String wordPack, Boolean hasBlank,
                             @Min(1) long budgetPoints, @NotNull @Valid Selection coach,
                             @NotNull @Size(min=4,max=10) List<@NotNull @Valid Participant> participants,
                             String gameId, Map<String,Object> rules, String learningMode,
                             String initialStrategyVersionId, Long randomSeed, Long trainingCheckpointPoints) {
        public Experiment {
            // Werewolf requests omit this undercover-only option. Keep the default local to this optional field.
            hasBlank=Boolean.TRUE.equals(hasBlank);
        }
    }

    public record Evaluation(@NotBlank String participantId, @Min(1) @Max(1000) int pairs,
                             String candidateExperienceId, String candidateStrategyVersionId,
                             String operationId, String targetRole, Long randomSeed) {
    }
    public record Review(@NotBlank @Pattern(regexp="[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}") String operationId,
                         @NotNull @Valid Selection coach) {}
    public record Budget(@Min(1) long budgetPoints) {
        @com.fasterxml.jackson.annotation.JsonCreator(mode=com.fasterxml.jackson.annotation.JsonCreator.Mode.DELEGATING)
        public static Budget from(Map<String,Object> body) {
            var points=body==null?null:body.get("budgetPoints");
            if(points instanceof java.math.BigInteger value) return new Budget(value.longValueExact());
            if(points instanceof Integer || points instanceof Long) return new Budget(((Number)points).longValue());
            throw new IllegalArgumentException("budgetPoints 必须为正整数");
        }
    }
}
