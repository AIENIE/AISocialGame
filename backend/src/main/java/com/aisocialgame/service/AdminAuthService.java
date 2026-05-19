package com.aisocialgame.service;

import com.aisocialgame.config.AppProperties;
import com.aisocialgame.exception.ApiException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AdminAuthService {
    private final AppProperties appProperties;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final StringRedisTemplate redisTemplate;
    private final String adminSessionKeyPrefix;

    @Autowired
    public AdminAuthService(AppProperties appProperties, ObjectProvider<StringRedisTemplate> redisTemplateProvider) {
        this.appProperties = appProperties;
        this.redisTemplate = redisTemplateProvider.getIfAvailable();
        this.adminSessionKeyPrefix = appProperties.getProjectKey() + ":admin:session:";
    }

    public AdminAuthService(AppProperties appProperties) {
        this.appProperties = appProperties;
        this.redisTemplate = null;
        this.adminSessionKeyPrefix = appProperties.getProjectKey() + ":admin:session:";
    }

    public String login(String username, String password) {
        String expectedUsername = appProperties.getAdmin().getUsername();
        String expectedPassword = appProperties.getAdmin().getPassword();
        if (!StringUtils.hasText(username) || !StringUtils.hasText(password)
                || !username.equals(expectedUsername) || !password.equals(expectedPassword)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "管理员账号或密码错误");
        }
        String token = UUID.randomUUID().toString();
        Duration ttl = Duration.ofHours(Math.max(1, appProperties.getAdmin().getTokenTtlHours()));
        if (redisTemplate != null) {
            redisTemplate.opsForValue().set(adminSessionKey(token), username, ttl);
        } else {
            sessions.put(token, new Session(username, Instant.now().plus(ttl)));
        }
        return token;
    }

    public String requireAdmin(String token) {
        if (!StringUtils.hasText(token)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "缺少管理员令牌");
        }
        if (redisTemplate != null) {
            String username = redisTemplate.opsForValue().get(adminSessionKey(token));
            if (!StringUtils.hasText(username)) {
                throw new ApiException(HttpStatus.UNAUTHORIZED, "管理员登录已过期");
            }
            return username;
        }
        Session session = sessions.get(token);
        if (session == null || session.expiresAt().isBefore(Instant.now())) {
            sessions.remove(token);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "管理员登录已过期");
        }
        return session.username();
    }

    public String getDisplayName() {
        return appProperties.getAdmin().getDisplayName();
    }

    @Scheduled(fixedDelayString = "${app.admin.session-cleanup-interval-ms:300000}")
    public void cleanupExpiredSessions() {
        if (redisTemplate != null || sessions.isEmpty()) {
            return;
        }
        Instant now = Instant.now();
        sessions.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
    }

    private String adminSessionKey(String token) {
        return adminSessionKeyPrefix + token;
    }

    private record Session(String username, Instant expiresAt) {
    }
}
