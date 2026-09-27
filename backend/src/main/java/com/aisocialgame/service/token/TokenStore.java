package com.aisocialgame.service.token;

public interface TokenStore {
    void store(String token, String userId, long externalUserId, String sessionId);

    SessionRecord getSession(String token);

    void revoke(String token);
}
