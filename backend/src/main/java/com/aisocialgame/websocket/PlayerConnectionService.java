package com.aisocialgame.websocket;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class PlayerConnectionService {
    private final Map<String, ConnectionState> byPlayer = new ConcurrentHashMap<>();
    private final Map<String, String> playerBySession = new ConcurrentHashMap<>();
    private final Duration staleThreshold;
    private final Duration retention;

    public PlayerConnectionService(@Value("${connection.disconnect-threshold-seconds:15}") long disconnectThresholdSeconds) {
        this.staleThreshold = Duration.ofSeconds(Math.max(5, disconnectThresholdSeconds));
        this.retention = this.staleThreshold.multipliedBy(4);
    }

    public void onConnect(String playerId, String roomId, String sessionId) {
        if (!StringUtils.hasText(playerId) || !StringUtils.hasText(sessionId)) {
            return;
        }
        ConnectionState state = new ConnectionState(playerId, roomId, sessionId, LocalDateTime.now());
        byPlayer.put(playerId, state);
        playerBySession.put(sessionId, playerId);
    }

    public void onDisconnect(String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            return;
        }
        String playerId = playerBySession.remove(sessionId);
        if (!StringUtils.hasText(playerId)) {
            return;
        }
        ConnectionState existing = byPlayer.get(playerId);
        if (existing != null && sessionId.equals(existing.sessionId())) {
            byPlayer.remove(playerId);
        }
    }

    public void markActive(String playerId, String roomId) {
        if (!StringUtils.hasText(playerId)) {
            return;
        }
        ConnectionState current = byPlayer.get(playerId);
        if (current != null) {
            byPlayer.put(playerId, new ConnectionState(playerId, current.roomId(), current.sessionId(), LocalDateTime.now()));
            return;
        }
        byPlayer.put(playerId, new ConnectionState(playerId, roomId, null, LocalDateTime.now()));
    }

    public boolean isOnline(String playerId) {
        ConnectionState state = byPlayer.get(playerId);
        if (state == null) {
            return false;
        }
        if (StringUtils.hasText(state.sessionId())) {
            return true;
        }
        return Duration.between(state.lastActiveAt(), LocalDateTime.now()).compareTo(staleThreshold) < 0;
    }

    @Scheduled(fixedDelayString = "${app.websocket.connection-cleanup-interval-ms:300000}")
    public void cleanupStaleConnections() {
        LocalDateTime cutoff = LocalDateTime.now().minus(retention);
        byPlayer.entrySet().removeIf(entry -> {
            ConnectionState state = entry.getValue();
            if (StringUtils.hasText(state.sessionId()) || state.lastActiveAt().isAfter(cutoff)) {
                return false;
            }
            return true;
        });
        playerBySession.entrySet().removeIf(entry -> !byPlayer.containsKey(entry.getValue()));
    }

    private record ConnectionState(String playerId, String roomId, String sessionId, LocalDateTime lastActiveAt) {
    }
}
