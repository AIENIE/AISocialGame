package com.aisocialgame.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.HashMap;
import java.util.Map;

public class PlayerAction {
    @NotBlank(message = "动作类型不能为空")
    @Pattern(regexp = "(?i)^(SPEAK|VOTE|NIGHT_ACTION)$", message = "动作类型不支持")
    private String type;

    @Size(max = 1000)
    private String content;

    @Size(max = 128)
    private String targetPlayerId;
    private boolean abstain;

    @Pattern(regexp = "^$|^(WOLF_KILL|SEER_CHECK|WITCH_SAVE|WITCH_POISON|WEREWOLF|SEER|WITCH)$", message = "夜晚行动类型不支持")
    private String nightAction;
    private boolean useHeal;

    @Size(max = 16)
    private Map<String, Object> extra = new HashMap<>();

    public String getType() { return type; }
    public String getContent() { return content; }
    public String getTargetPlayerId() { return targetPlayerId; }
    public boolean isAbstain() { return abstain; }
    public String getNightAction() { return nightAction; }
    public boolean isUseHeal() { return useHeal; }
    public Map<String, Object> getExtra() { return extra; }

    public void setType(String type) { this.type = type; }
    public void setContent(String content) { this.content = content; }
    public void setTargetPlayerId(String targetPlayerId) { this.targetPlayerId = targetPlayerId; }
    public void setAbstain(boolean abstain) { this.abstain = abstain; }
    public void setNightAction(String nightAction) { this.nightAction = nightAction; }
    public void setUseHeal(boolean useHeal) { this.useHeal = useHeal; }
    public void setExtra(Map<String, Object> extra) { this.extra = extra != null ? extra : new HashMap<>(); }
}
