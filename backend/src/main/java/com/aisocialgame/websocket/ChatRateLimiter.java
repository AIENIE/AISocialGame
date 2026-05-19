package com.aisocialgame.websocket;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ChatRateLimiter {
    private static final Duration MIN_INTERVAL = Duration.ofSeconds(3);
    private static final Duration RETENTION = Duration.ofMinutes(30);
    private final Map<String, Instant> lastSendAt = new ConcurrentHashMap<>();

    public boolean allowMessage(String playerId) {
        if (!StringUtils.hasText(playerId)) {
            return false;
        }
        Instant now = Instant.now();
        Instant previous = lastSendAt.get(playerId);
        if (previous != null && Duration.between(previous, now).compareTo(MIN_INTERVAL) < 0) {
            return false;
        }
        lastSendAt.put(playerId, now);
        return true;
    }

    @Scheduled(fixedDelayString = "${app.websocket.rate-limit-cleanup-interval-ms:300000}")
    public void cleanupInactivePlayers() {
        Instant cutoff = Instant.now().minus(RETENTION);
        lastSendAt.entrySet().removeIf(entry -> entry.getValue().isBefore(cutoff));
    }
}
