package com.aisocialgame.model;

public class Persona {
    private String id;
    private String name;
    private String trait;
    private String avatar;
    private String speechStyle;
    private String strategyStyle;
    private int difficultyLevel;
    private String memorySeed;

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

    public String getId() { return id; }
    public String getName() { return name; }
    public String getTrait() { return trait; }
    public String getAvatar() { return avatar; }
    public String getSpeechStyle() { return speechStyle; }
    public String getStrategyStyle() { return strategyStyle; }
    public int getDifficultyLevel() { return difficultyLevel; }
    public String getMemorySeed() { return memorySeed; }
}
