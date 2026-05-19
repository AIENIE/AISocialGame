package com.aisocialgame.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class NightActionRequest {
    @NotBlank
    @Pattern(regexp = "^(WOLF_KILL|SEER_CHECK|WITCH_SAVE|WITCH_POISON|WEREWOLF|SEER|WITCH)$", message = "夜晚行动类型不支持")
    private String action;

    @Size(max = 128)
    private String targetPlayerId;
    private boolean useHeal;

    public String getAction() {
        return action;
    }

    public String getTargetPlayerId() {
        return targetPlayerId;
    }

    public boolean isUseHeal() {
        return useHeal;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public void setTargetPlayerId(String targetPlayerId) {
        this.targetPlayerId = targetPlayerId;
    }

    public void setUseHeal(boolean useHeal) {
        this.useHeal = useHeal;
    }
}
