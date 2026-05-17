package com.aisocialgame.dto;

import java.util.HashMap;
import java.util.Map;

public class PlayerAction {
    private String type;
    private String content;
    private String targetPlayerId;
    private boolean abstain;
    private String nightAction;
    private boolean useHeal;
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
