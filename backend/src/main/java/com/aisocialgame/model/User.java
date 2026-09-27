package com.aisocialgame.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "users")
public class User {
    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false, unique = true, length = 191)
    private String email;

    @Column(unique = true, length = 64)
    private String username;

    @Column(name = "external_user_id", unique = true)
    private Long externalUserId;

    @JsonIgnore
    @Column(nullable = false)
    private String password;

    @JsonIgnore
    @Column(name = "session_id", length = 128)
    private String sessionId;

    @JsonIgnore
    @Column(name = "access_token", length = 2048)
    private String accessToken;

    @Column(nullable = false, length = 64)
    private String nickname;

    private String avatar;
    // Rewards use atomic SQL increments; a detached profile must never overwrite the balance.
    @Column(updatable = false)
    private int coins;
    private int level;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public User() {}

    public User(String id, String email, String password, String nickname, String avatar, int coins, int level) {
        this.id = id;
        this.email = email;
        this.password = password;
        this.nickname = nickname;
        this.avatar = avatar;
        this.coins = coins;
        this.level = level;
    }

    @PrePersist
    public void onCreate() {
        if (this.id == null) {
            this.id = UUID.randomUUID().toString();
        }
        this.createdAt = LocalDateTime.now();
        this.updatedAt = this.createdAt;
    }

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public String getId() { return id; }
    public String getEmail() { return email; }
    public String getUsername() { return username; }
    public Long getExternalUserId() { return externalUserId; }
    public String getPassword() { return password; }
    public String getSessionId() { return sessionId; }
    public String getAccessToken() { return accessToken; }
    public String getNickname() { return nickname; }
    public String getAvatar() { return avatar; }
    public int getCoins() { return coins; }
    public int getLevel() { return level; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    public void setId(String id) { this.id = id; }
    public void setEmail(String email) { this.email = email; }
    public void setUsername(String username) { this.username = username; }
    public void setExternalUserId(Long externalUserId) { this.externalUserId = externalUserId; }
    public void setPassword(String password) { this.password = password; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public void setAccessToken(String accessToken) { this.accessToken = accessToken; }
    public void setNickname(String nickname) { this.nickname = nickname; }
    public void setAvatar(String avatar) { this.avatar = avatar; }
    public void setCoins(int coins) { this.coins = coins; }
    public void setLevel(int level) { this.level = level; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
