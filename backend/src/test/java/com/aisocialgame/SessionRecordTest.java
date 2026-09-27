package com.aisocialgame;

import com.aisocialgame.service.token.InMemoryTokenStore;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SessionRecordTest {
    @Test void oldTokensAndExpiredOrRevokedSessionsAreRejected() {
        var store = new InMemoryTokenStore(Duration.ofHours(1));
        store.store("old-uuid", "user", 1, "sso");
        assertNull(store.getSession("old-uuid"));
        store.store("v2.token", "user", 1, "sso");
        assertEquals("sso", store.getSession("v2.token").sessionId());
        store.revoke("v2.token"); store.revoke("v2.token");
        assertNull(store.getSession("v2.token"));
        var expired = new InMemoryTokenStore(Duration.ZERO);
        expired.store("v2.token", "user", 1, "sso");
        assertNull(expired.getSession("v2.token"));
    }
}
