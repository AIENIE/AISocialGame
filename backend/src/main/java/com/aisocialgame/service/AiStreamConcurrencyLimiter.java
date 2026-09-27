package com.aisocialgame.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class AiStreamConcurrencyLimiter {
    private final Map<String, Integer> activeByUser = new ConcurrentHashMap<>();
    private final int maxPerUser;

    public AiStreamConcurrencyLimiter(@Value("${app.ai.stream.max-concurrent-per-user:2}") int maxPerUser) {
        this.maxPerUser = Math.max(1, maxPerUser);
    }

    public Permit tryAcquire(String userId) {
        AtomicBoolean acquired = new AtomicBoolean();
        activeByUser.compute(userId, (key, count) -> {
            int active = count == null ? 0 : count;
            if (active >= maxPerUser) return count;
            acquired.set(true);
            return active + 1;
        });
        return acquired.get() ? new Permit(userId) : null;
    }

    public final class Permit implements AutoCloseable {
        private final String userId;
        private final AtomicBoolean released = new AtomicBoolean();
        private Permit(String userId) { this.userId = userId; }
        @Override public void close() {
            if (released.compareAndSet(false, true)) {
                activeByUser.computeIfPresent(userId, (key, count) -> count <= 1 ? null : count - 1);
            }
        }
    }
}
