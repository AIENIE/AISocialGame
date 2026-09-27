package com.aisocialgame.service.token;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

public class RedisTokenStore implements TokenStore {
    private static final String DEFAULT_KEY_PREFIX = "auth:token:";
    private final StringRedisTemplate redisTemplate;
    private final Duration ttl;
    private final String keyPrefix;

    public RedisTokenStore(StringRedisTemplate redisTemplate, Duration ttl, String keyPrefix) {
        this.redisTemplate = redisTemplate;
        this.ttl = ttl;
        this.keyPrefix = keyPrefix == null || keyPrefix.isBlank() ? DEFAULT_KEY_PREFIX : keyPrefix;
    }

    private final com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();

    @Override
    public void store(String token, String userId, long externalUserId, String sessionId) {
        if (!validToken(token)) throw new IllegalArgumentException("unsupported session token");
        try {
            redisTemplate.opsForValue().set(buildKey(token), json.writeValueAsString(SessionRecord.create(userId, externalUserId, sessionId, ttl)), ttl);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("Cannot encode session", exception);
        }
    }

    @Override
    public SessionRecord getSession(String token) {
        if (!validToken(token)) return null;
        String raw = redisTemplate.opsForValue().get(buildKey(token));
        if (raw == null) return null;
        try {
            SessionRecord session = json.readValue(raw, SessionRecord.class);
            return session.active() ? session : null;
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            return null;
        }
    }

    @Override
    public void revoke(String token) {
        if (!validToken(token)) return;
        redisTemplate.execute(new org.springframework.data.redis.core.script.DefaultRedisScript<Long>(
                "local s=redis.call('GET',KEYS[1]); if not s then return 0 end; local v=cjson.decode(s); v.revoked=true; redis.call('SET',KEYS[1],cjson.encode(v),'KEEPTTL'); return 1", Long.class), java.util.List.of(buildKey(token)));
    }

    // Delete only this project's legacy UUID tokens. They cannot become usable again on rollback.
    @jakarta.annotation.PostConstruct
    public void invalidateLegacyTokens() {
        try (var keys = redisTemplate.scan(org.springframework.data.redis.core.ScanOptions.scanOptions().match(keyPrefix + "*").count(256).build())) {
            while (keys.hasNext()) {
                String key = keys.next();
                if (key.startsWith(keyPrefix) && key.substring(keyPrefix.length()).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) redisTemplate.delete(key);
            }
        }
    }

    private boolean validToken(String token) {
        return token != null && token.matches("v2\\.[0-9a-f-]{36}");
    }

    private String buildKey(String token) {
        return keyPrefix + "v2:" + token;
    }
}
