package com.aisocialgame.model;

import com.aisocialgame.engine.PhaseDefinition;
import com.aisocialgame.engine.RoleDefinition;

import java.util.List;

public class Game {
    private String id;
    private String name;
    private String description;
    private String coverUrl;
    private List<String> tags;
    private int minPlayers;
    private int maxPlayers;
    private GameStatus status;
    private int onlineCount;
    private List<GameConfigOption> configSchema;
    private boolean engineBacked;
    private List<PhaseDefinition> phaseDefinitions;
    private List<RoleDefinition> roleDefinitions;

    public Game() {}

    public Game(String id, String name, String description, String coverUrl, List<String> tags,
                int minPlayers, int maxPlayers, GameStatus status, int onlineCount, List<GameConfigOption> configSchema) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.coverUrl = coverUrl;
        this.tags = tags;
        this.minPlayers = minPlayers;
        this.maxPlayers = maxPlayers;
        this.status = status;
        this.onlineCount = onlineCount;
        this.configSchema = configSchema;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getCoverUrl() {
        return coverUrl;
    }

    public List<String> getTags() {
        return tags;
    }

    public int getMinPlayers() {
        return minPlayers;
    }

    public int getMaxPlayers() {
        return maxPlayers;
    }

    public GameStatus getStatus() {
        return status;
    }

    public int getOnlineCount() {
        return onlineCount;
    }

    public void setOnlineCount(int onlineCount) {
        this.onlineCount = onlineCount;
    }

    public List<GameConfigOption> getConfigSchema() {
        return configSchema;
    }

    public boolean isEngineBacked() {
        return engineBacked;
    }

    public void setEngineBacked(boolean engineBacked) {
        this.engineBacked = engineBacked;
    }

    public List<PhaseDefinition> getPhaseDefinitions() {
        return phaseDefinitions;
    }

    public void setPhaseDefinitions(List<PhaseDefinition> phaseDefinitions) {
        this.phaseDefinitions = phaseDefinitions;
    }

    public List<RoleDefinition> getRoleDefinitions() {
        return roleDefinitions;
    }

    public void setRoleDefinitions(List<RoleDefinition> roleDefinitions) {
        this.roleDefinitions = roleDefinitions;
    }
}
