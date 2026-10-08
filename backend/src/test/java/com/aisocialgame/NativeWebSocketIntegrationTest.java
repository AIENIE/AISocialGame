package com.aisocialgame;

import com.aisocialgame.model.Room;
import com.aisocialgame.model.RoomStatus;
import com.aisocialgame.model.User;
import com.aisocialgame.repository.RoomRepository;
import com.aisocialgame.service.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class NativeWebSocketIntegrationTest {
    @LocalServerPort int port;
    @Autowired RoomRepository rooms;
    @Autowired com.aisocialgame.service.RoomLifecycle lifecycle;
    @Autowired org.springframework.context.ApplicationEventPublisher events;
    @MockitoBean AuthService auth;

    @Test void privateSubscriptionHandshakeAndLogoutAreEnforcedOnRealTransport() throws Exception {
        String ownerId = UUID.randomUUID().toString();
        String outsiderId = UUID.randomUUID().toString();
        String roomId = UUID.randomUUID().toString();
        Room room = new Room(roomId, "undercover", "private", RoomStatus.WAITING, 4, true, null, "text", Map.of());
        room.setHostUserId(ownerId);
        rooms.saveAndFlush(room);
        User owner = new User(); owner.setId(ownerId);
        User outsider = new User(); outsider.setId(outsiderId);
        when(auth.authenticate("v2.owner")).thenReturn(owner);
        when(auth.authenticate("v2.outsider")).thenReturn(outsider);
        when(auth.isSessionActive("v2.owner")).thenReturn(true);
        when(auth.isSessionActive("v2.outsider")).thenReturn(true);

        HttpClient client = HttpClient.newHttpClient();
        try {
            Frames ownerFrames = new Frames();
            WebSocket ownerSocket = client.newWebSocketBuilder().buildAsync(
                    URI.create("ws://127.0.0.1:" + port + "/ws"), ownerFrames).get(5, TimeUnit.SECONDS);
            connect(ownerSocket, "v2.owner");
            assertTrue(ownerFrames.await("CONNECTED", 5).startsWith("CONNECTED"));
            send(ownerSocket, "SUBSCRIBE\nid:private\ndestination:/user/queue/private\n\n\0");
            send(ownerSocket, "SUBSCRIBE\nid:state\ndestination:/topic/room/" + roomId + "/state\n\n\0");
            send(ownerSocket, "SEND\ndestination:/app/room/" + roomId + "/sync\ncontent-type:application/json\n\n{\"nonce\":\"native-check\"}\0");
            String synced = ownerFrames.await("SYNC_READY", 5);
            assertTrue(synced.contains("native-check"));

            Frames outsiderFrames = new Frames();
            WebSocket outsiderSocket = client.newWebSocketBuilder().buildAsync(
                    URI.create("ws://127.0.0.1:" + port + "/ws"), outsiderFrames).get(5, TimeUnit.SECONDS);
            connect(outsiderSocket, "v2.outsider");
            assertTrue(outsiderFrames.await("CONNECTED", 5).startsWith("CONNECTED"));
            send(outsiderSocket, "SUBSCRIBE\nid:denied\ndestination:/topic/room/" + roomId + "/state\n\n\0");
            assertNotNull(outsiderFrames.closed.get(5, TimeUnit.SECONDS));

            events.publishEvent(new AuthService.SessionRevoked("v2.owner"));
            assertNotNull(ownerFrames.closed.get(5, TimeUnit.SECONDS));
        } finally { client.shutdownNow(); }
    }

    @Test void clientCannotPublishDirectlyToBroker() throws Exception {
        String userId = UUID.randomUUID().toString();
        User user = new User(); user.setId(userId);
        when(auth.authenticate("v2.publisher")).thenReturn(user);
        when(auth.isSessionActive("v2.publisher")).thenReturn(true);
        HttpClient client = HttpClient.newHttpClient();
        try {
            Frames frames = new Frames();
            WebSocket socket = client.newWebSocketBuilder().buildAsync(
                    URI.create("ws://127.0.0.1:" + port + "/ws"), frames).get(5, TimeUnit.SECONDS);
            connect(socket, "v2.publisher");
            assertTrue(frames.await("CONNECTED", 5).startsWith("CONNECTED"));
            send(socket, "SEND\ndestination:/topic/room/" + UUID.randomUUID() + "/state\n\n{\"forged\":true}\0");
            assertNotNull(frames.closed.get(5, TimeUnit.SECONDS));
        } finally { client.shutdownNow(); }
    }

    @Test void sockJsWebSocketTransportCompletesTheSameOrderedSync() throws Exception {
        String userId = UUID.randomUUID().toString();
        String roomId = UUID.randomUUID().toString();
        Room room = new Room(roomId, "undercover", "public", RoomStatus.WAITING, 4, false, null, "text", Map.of());
        room.setHostUserId(userId);
        rooms.saveAndFlush(room);
        User user = new User(); user.setId(userId);
        when(auth.authenticate("v2.sockjs")).thenReturn(user);
        when(auth.isSessionActive("v2.sockjs")).thenReturn(true);
        HttpClient client = HttpClient.newHttpClient();
        try {
            var info = client.send(java.net.http.HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + port + "/ws/info")).GET().build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            assertEquals(200, info.statusCode());
            assertTrue(info.body().contains("\"websocket\":true"));
            Frames frames = new Frames();
            WebSocket socket = client.newWebSocketBuilder().buildAsync(
                    URI.create("ws://127.0.0.1:" + port + "/ws/000/" + UUID.randomUUID() + "/websocket"), frames)
                    .get(5, TimeUnit.SECONDS);
            assertEquals("o", frames.await("o", 5));
            sendSockJs(socket, "CONNECT\naccept-version:1.2\nhost:localhost\nAuthorization:Bearer v2.sockjs\nheart-beat:10000,10000\n\n\0");
            assertTrue(frames.await("CONNECTED", 5).contains("CONNECTED"));
            sendSockJs(socket, "SUBSCRIBE\nid:private\ndestination:/user/queue/private\n\n\0");
            sendSockJs(socket, "SUBSCRIBE\nid:state\ndestination:/topic/room/" + roomId + "/state\n\n\0");
            sendSockJs(socket, "SEND\ndestination:/app/room/" + roomId + "/sync\ncontent-type:application/json\n\n{\"nonce\":\"sockjs-check\"}\0");
            assertTrue(frames.await("SYNC_READY", 5).contains("sockjs-check"));
            socket.abort();
        } finally { client.shutdownNow(); }
    }

    @Test void expiryNotifiesMembersAndRejectsChatAndNewSubscriptionsOnRealTransport() throws Exception {
        String ownerId = UUID.randomUUID().toString();
        String roomId = UUID.randomUUID().toString();
        Room room = new Room(roomId, "undercover", "expiry", RoomStatus.WAITING, 4, true, null, "text", Map.of());
        room.setHostUserId(ownerId); rooms.saveAndFlush(room);
        User owner = new User(); owner.setId(ownerId);
        when(auth.authenticate("v2.expiry")).thenReturn(owner);
        when(auth.isSessionActive("v2.expiry")).thenReturn(true);
        HttpClient client = HttpClient.newHttpClient();
        try {
            Frames frames = new Frames();
            WebSocket socket = client.newWebSocketBuilder().buildAsync(
                    URI.create("ws://127.0.0.1:" + port + "/ws"), frames).get(5, TimeUnit.SECONDS);
            connect(socket, "v2.expiry"); frames.await("CONNECTED", 5);
            send(socket, "SUBSCRIBE\nid:expiry-private\ndestination:/user/queue/private\n\n\0");
            send(socket, "SEND\ndestination:/app/room/" + roomId + "/sync\ncontent-type:application/json\n\n{\"nonce\":\"expiry-ready\"}\0");
            frames.await("SYNC_READY", 5);
            Room current = rooms.findById(roomId).orElseThrow();
            current.setWaitingSince(java.time.LocalDateTime.now().minusHours(4)); rooms.saveAndFlush(current);
            lifecycle.sweep();
            assertTrue(frames.await("ROOM_EXPIRED", 5).contains(roomId));
            assertEquals(RoomStatus.EXPIRED, rooms.findById(roomId).orElseThrow().getStatus());
            send(socket, "SEND\ndestination:/app/room/" + roomId + "/chat\ncontent-type:application/json\n\n{\"type\":\"TEXT\",\"content\":\"late\"}\0");
            assertNotNull(frames.closed.get(5, TimeUnit.SECONDS));
            Frames rejected = new Frames();
            WebSocket reconnect = client.newWebSocketBuilder().buildAsync(
                    URI.create("ws://127.0.0.1:" + port + "/ws"), rejected).get(5, TimeUnit.SECONDS);
            connect(reconnect, "v2.expiry"); rejected.await("CONNECTED", 5);
            send(reconnect, "SUBSCRIBE\nid:expired-state\ndestination:/topic/room/" + roomId + "/state\n\n\0");
            assertNotNull(rejected.closed.get(5, TimeUnit.SECONDS));
        } finally { client.shutdownNow(); }
    }

    private static void connect(WebSocket socket, String token) {
        send(socket, "CONNECT\naccept-version:1.2\nhost:localhost\nAuthorization:Bearer " + token
                + "\nheart-beat:10000,10000\n\n\0");
    }

    private static void send(WebSocket socket, String frame) { socket.sendText(frame, true).join(); }
    private static void sendSockJs(WebSocket socket, String frame) throws Exception {
        send(socket, new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(java.util.List.of(frame)));
    }

    private static class Frames implements WebSocket.Listener {
        private final BlockingQueue<String> incoming = new LinkedBlockingQueue<>();
        private final StringBuilder partial = new StringBuilder();
        private final CompletableFuture<Integer> closed = new CompletableFuture<>();

        @Override public void onOpen(WebSocket webSocket) { webSocket.request(1); }
        @Override public java.util.concurrent.CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) { incoming.offer(partial.toString()); partial.setLength(0); }
            webSocket.request(1);
            return null;
        }
        @Override public java.util.concurrent.CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closed.complete(statusCode);
            return null;
        }
        @Override public void onError(WebSocket webSocket, Throwable error) { closed.completeExceptionally(error); }
        String await(String marker, int seconds) throws InterruptedException {
            long until = System.nanoTime() + Duration.ofSeconds(seconds).toNanos();
            while (System.nanoTime() < until) {
                String text = incoming.poll(Math.max(1, TimeUnit.NANOSECONDS.toMillis(until - System.nanoTime())), TimeUnit.MILLISECONDS);
                if (text == null) break;
                if (text.contains(marker)) return text;
            }
            fail("STOMP frame not received: " + marker);
            return "";
        }
    }
}
