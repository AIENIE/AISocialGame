package com.aisocialgame.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class AiStreamConcurrencyLimiter {
    private final Map<String, AtomicInteger> activeByUser = new ConcurrentHashMap<>();
    private final int maxPerUser;

    public AiStreamConcurrencyLimiter(@Value("${app.ai.stream.max-concurrent-per-user:2}") int maxPerUser) {
        this.maxPerUser = Math.max(1, maxPerUser);
    }

    public boolean tryAcquire(String userId) {
        AtomicInteger counter = activeByUser.computeIfAbsent(userId, ignored -> new AtomicInteger());
        while (true) {
            int current = counter.get();
            if (current >= maxPerUser) {
                return false;
            }
            if (counter.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    public void release(String userId) {
        AtomicInteger counter = activeByUser.get(userId);
        if (counter == null) {
            return;
        }
        if (counter.decrementAndGet() <= 0) {
            activeByUser.remove(userId, counter);
        }
    }
}
