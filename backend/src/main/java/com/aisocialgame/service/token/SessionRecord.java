package com.aisocialgame.service.token;

import java.time.Duration;
import java.time.Instant;

/** Each local token is permanently bound to the original SSO session. */
public record SessionRecord(int version, String userId, long externalUserId, String sessionId,
                            long issuedAt, long expiresAt, boolean revoked) {
    public static SessionRecord create(String userId, long externalUserId, String sessionId, Duration ttl) {
        long now = Instant.now().toEpochMilli();
        return new SessionRecord(2, userId, externalUserId, sessionId, now, now + ttl.toMillis(), false);
    }

    public boolean active() {
        return version == 2 && !revoked && externalUserId > 0 && userId != null && !userId.isBlank()
                && sessionId != null && !sessionId.isBlank() && expiresAt > System.currentTimeMillis();
    }

    public SessionRecord revoke() {
        return new SessionRecord(version, userId, externalUserId, sessionId, issuedAt, expiresAt, true);
    }
}
