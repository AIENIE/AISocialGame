package com.aisocialgame.dto.admin;

import com.aisocialgame.model.AiPersonaMemory;

import java.time.LocalDateTime;

public class AdminAiPersonaMemoryView {
    private final Long id;
    private final String personaId;
    private final String gameId;
    private final String roleKey;
    private final String memorySummary;
    private final String strategyNotes;
    private final String mistakeNotes;
    private final String speechPatterns;
    private final int gamesPlayed;
    private final LocalDateTime updatedAt;

    public AdminAiPersonaMemoryView(AiPersonaMemory memory) {
        this.id = memory.getId();
        this.personaId = memory.getPersonaId();
        this.gameId = memory.getGameId();
        this.roleKey = memory.getRoleKey();
        this.memorySummary = memory.getMemorySummary();
        this.strategyNotes = memory.getStrategyNotes();
        this.mistakeNotes = memory.getMistakeNotes();
        this.speechPatterns = memory.getSpeechPatterns();
        this.gamesPlayed = memory.getGamesPlayed();
        this.updatedAt = memory.getUpdatedAt();
    }

    public Long getId() { return id; }
    public String getPersonaId() { return personaId; }
    public String getGameId() { return gameId; }
    public String getRoleKey() { return roleKey; }
    public String getMemorySummary() { return memorySummary; }
    public String getStrategyNotes() { return strategyNotes; }
    public String getMistakeNotes() { return mistakeNotes; }
    public String getSpeechPatterns() { return speechPatterns; }
    public int getGamesPlayed() { return gamesPlayed; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
