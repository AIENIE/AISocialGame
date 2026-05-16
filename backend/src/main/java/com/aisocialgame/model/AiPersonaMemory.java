package com.aisocialgame.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "ai_persona_memories",
        uniqueConstraints = @UniqueConstraint(name = "uk_ai_persona_memory_scope", columnNames = {"persona_id", "game_id", "role_key"})
)
public class AiPersonaMemory {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "persona_id", nullable = false, length = 64)
    private String personaId;

    @Column(name = "game_id", nullable = false, length = 64)
    private String gameId;

    @Column(name = "role_key", nullable = false, length = 64)
    private String roleKey;

    @Column(columnDefinition = "LONGTEXT")
    private String memorySummary = "";

    @Column(columnDefinition = "LONGTEXT")
    private String strategyNotes = "";

    @Column(columnDefinition = "LONGTEXT")
    private String mistakeNotes = "";

    @Column(columnDefinition = "LONGTEXT")
    private String speechPatterns = "";

    private int gamesPlayed;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public AiPersonaMemory() {}

    public AiPersonaMemory(String personaId, String gameId, String roleKey) {
        this.personaId = personaId;
        this.gameId = gameId;
        this.roleKey = roleKey;
    }

    @PrePersist
    public void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    public void onUpdate() {
        updatedAt = LocalDateTime.now();
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
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    public void setId(Long id) { this.id = id; }
    public void setPersonaId(String personaId) { this.personaId = personaId; }
    public void setGameId(String gameId) { this.gameId = gameId; }
    public void setRoleKey(String roleKey) { this.roleKey = roleKey; }
    public void setMemorySummary(String memorySummary) { this.memorySummary = memorySummary; }
    public void setStrategyNotes(String strategyNotes) { this.strategyNotes = strategyNotes; }
    public void setMistakeNotes(String mistakeNotes) { this.mistakeNotes = mistakeNotes; }
    public void setSpeechPatterns(String speechPatterns) { this.speechPatterns = speechPatterns; }
    public void setGamesPlayed(int gamesPlayed) { this.gamesPlayed = gamesPlayed; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
