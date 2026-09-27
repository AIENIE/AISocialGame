package com.aisocialgame.service.token;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryTokenStore implements TokenStore {
    private final Map<String, SessionRecord> tokens = new ConcurrentHashMap<>();
    private final Duration ttl;

    public InMemoryTokenStore(Duration ttl) {
        this.ttl = ttl;
    }

    @Override
    public void store(String token, String userId, long externalUserId, String sessionId) {
        tokens.put(token, SessionRecord.create(userId, externalUserId, sessionId, ttl));
    }

    @Override
    public SessionRecord getSession(String token) {
        if (token == null || !token.startsWith("v2.")) return null;
        SessionRecord record = tokens.get(token);
        if (record == null) {
            return null;
        }
        if (!record.active()) {
            return null;
        }
        return record;
    }

    @Override
    public void revoke(String token) {
        if (token != null) tokens.computeIfPresent(token, (key, session) -> session.revoke());
    }

}
