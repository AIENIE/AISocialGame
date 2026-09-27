package com.aisocialgame.service;

import com.aisocialgame.exception.ApiException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Controlled tests only; runtime always uses the Redis implementation. */
@Service
@Profile("test")
public class InMemoryWriteRateLimiter implements WriteRateLimiter {
    private record Window(long expiresAt, int count) { }
    private final Map<String, Window> windows = new HashMap<>();
    @Override public synchronized void require(String userId, List<Limit> limits) {
        long now = System.currentTimeMillis();
        windows.entrySet().removeIf(entry -> entry.getValue().expiresAt <= now);
        boolean allowed = true;
        for (Limit limit : limits) {
            String key = userId + ":" + limit.scope();
            Window previous = windows.getOrDefault(key, new Window(now + 60000, 0));
            Window current = new Window(previous.expiresAt, previous.count + 1);
            windows.put(key, current); allowed &= current.count <= limit.maximum();
        }
        if (!allowed) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "操作过于频繁，请稍后再试", "RATE_LIMITED", Map.of());
    }
}
