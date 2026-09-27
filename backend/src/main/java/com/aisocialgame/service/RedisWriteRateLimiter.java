package com.aisocialgame.service;

import com.aisocialgame.exception.ApiException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
@Profile("!test")
public class RedisWriteRateLimiter implements WriteRateLimiter {
    private static final DefaultRedisScript<Long> WINDOW = new DefaultRedisScript<>("""
            local allowed=1
            for i,key in ipairs(KEYS) do
              local count=redis.call('INCR',key)
              if count==1 then redis.call('PEXPIRE',key,60000) end
              if count>tonumber(ARGV[i]) then allowed=0 end
            end
            return allowed
            """, Long.class);
    private final StringRedisTemplate redis;
    private final String prefix;

    public RedisWriteRateLimiter(StringRedisTemplate redis, @Value("${app.project-key:ai-social-game}") String project) {
        this.redis = redis; this.prefix = project + ":write-rate:";
    }

    @Override public void require(String userId, List<Limit> limits) {
        try {
            // Same account hash tag keeps multi-key Lua atomic on Redis Cluster too.
            String account = "{" + hash(userId) + "}:";
            var keys = limits.stream().map(limit -> prefix + account + hash(limit.scope())).toList();
            var values = limits.stream().map(limit -> Integer.toString(limit.maximum())).toArray();
            Long result = redis.execute(WINDOW, keys, values);
            if (result == null) throw new IllegalStateException("Redis returned no limiter result");
            if (result != 1L) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "操作过于频繁，请稍后再试", "RATE_LIMITED", Map.of());
        } catch (ApiException rejected) { throw rejected; }
        catch (RuntimeException unavailable) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "限流服务暂不可用", "RATE_LIMIT_UNAVAILABLE", Map.of());
        }
    }

    private String hash(String value) {
        try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
