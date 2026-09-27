package com.aisocialgame.websocket;

import com.aisocialgame.service.AuthService;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

/** Owns transport closure, including idle subscriptions after token revocation. */
@Component
public class AuthenticatedSocketRegistry {
    private final AuthService auth;
    private final ConcurrentHashMap<String, Connection> connections = new ConcurrentHashMap<>();
    private final java.util.concurrent.ExecutorService checks = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore permits = new Semaphore(32);

    public AuthenticatedSocketRegistry(AuthService auth) { this.auth = auth; }

    public void opened(WebSocketSession session) { connections.put(session.getId(), new Connection(session)); }
    public void closed(String id) { connections.remove(id); }
    public void reject(String id) {
        Connection connection = id == null ? null : connections.get(id);
        if (connection != null) close(connection);
    }
    public void bind(String id, String token, String playerId) {
        Connection connection = connections.get(id);
        if (connection == null) throw new IllegalStateException("WebSocket transport unavailable");
        connection.playerId = playerId;
        connection.verifiedAt = System.currentTimeMillis();
        connection.token = token;
    }

    public String playerId(String id) {
        Connection connection = connections.get(id);
        return connection == null ? null : connection.playerId;
    }

    public boolean active(String id) {
        Connection connection = id == null ? null : connections.get(id);
        try {
            if (connection != null && connection.token != null && System.currentTimeMillis() - connection.verifiedAt <= 30000 && auth.isSessionActive(connection.token)) return true;
        } catch (RuntimeException ignored) { /* Fail closed when the session store is unavailable. */ }
        if (connection != null) close(connection);
        return false;
    }

    @EventListener
    public void revoked(AuthService.SessionRevoked event) {
        connections.values().stream().filter(value -> event.token().equals(value.token)).forEach(this::close);
    }

    @Scheduled(fixedDelayString = "${app.auth.websocket-recheck-ms:10000}")
    public void recheck() {
        for (Connection connection : connections.values().stream().sorted(java.util.Comparator.comparingLong(value -> value.verifiedAt)).toList()) {
            if (connection.token == null) continue;
            if (System.currentTimeMillis() - connection.verifiedAt > 30000) { close(connection); continue; }
            if (!connection.checking.compareAndSet(false, true)) continue;
            if (!permits.tryAcquire()) { connection.checking.set(false); continue; }
            try { checks.submit(() -> {
                try {
                    if (auth.authenticate(connection.token) == null) close(connection);
                    else connection.verifiedAt = System.currentTimeMillis();
                } catch (RuntimeException exception) { close(connection); }
                finally { connection.checking.set(false); permits.release(); }
            }); } catch (java.util.concurrent.RejectedExecutionException rejected) {
                connection.checking.set(false); permits.release(); close(connection);
            }
        }
    }

    private void close(Connection connection) {
        try { connection.session.close(CloseStatus.POLICY_VIOLATION); }
        catch (IOException ignored) { }
        finally { connections.remove(connection.session.getId(), connection); }
    }

    @PreDestroy public void stop() { checks.shutdownNow(); }

    private static final class Connection {
        final WebSocketSession session;
        final AtomicBoolean checking = new AtomicBoolean();
        volatile String token;
        volatile String playerId;
        volatile long verifiedAt;
        Connection(WebSocketSession session) { this.session = session; }
    }
}
