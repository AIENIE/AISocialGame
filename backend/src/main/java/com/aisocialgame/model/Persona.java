package com.aisocialgame.model;

import java.util.Map;

public class Persona {
    private String id;
    private String name;
    private String trait;
    private String avatar;
    private String speechStyle;
    private String strategyStyle;
    private int difficultyLevel;
    private String memorySeed;
    private int riskPreference = 2;
    private int emotionalRecovery = 2;
    private int sociability = 2;

    private Map<String, String> behaviorGuide = Map.of();
    private int presetVersion;

    public Persona() {}

    public Persona(String id, String name, String trait, String avatar) {
        this(id, name, trait, avatar, "", "", 1, "");
    }

    public Persona(String id, String name, String trait, String avatar, String speechStyle, String strategyStyle, int difficultyLevel, String memorySeed) {
        this.id = id;
        this.name = name;
        this.trait = trait;
        this.avatar = avatar;
        this.speechStyle = speechStyle;
        this.strategyStyle = strategyStyle;
        this.difficultyLevel = difficultyLevel;
        this.memorySeed = memorySeed;
    }

    public Persona(String id, String name, String trait, String avatar, String speechStyle, String strategyStyle, int difficultyLevel,
                   String memorySeed, int riskPreference, int emotionalRecovery, int sociability, Map<String, String> behaviorGuide, int presetVersion) {
        this(id, name, trait, avatar, speechStyle, strategyStyle, difficultyLevel, memorySeed);
        this.riskPreference = riskPreference; this.emotionalRecovery = emotionalRecovery; this.sociability = sociability;
        this.behaviorGuide = Map.copyOf(behaviorGuide); this.presetVersion = presetVersion;
    }
    public Map<String, String> getBehaviorGuide() { return behaviorGuide; }
    public int getPresetVersion() { return presetVersion; }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getTrait() { return trait; }
    public String getAvatar() { return avatar; }
    public String getSpeechStyle() { return speechStyle; }
    public String getStrategyStyle() { return strategyStyle; }
    public int getDifficultyLevel() { return difficultyLevel; }
    public String getMemorySeed() { return memorySeed; }
    public int getRiskPreference() { return riskPreference; }
    public int getEmotionalRecovery() { return emotionalRecovery; }
    public int getSociability() { return sociability; }
}
