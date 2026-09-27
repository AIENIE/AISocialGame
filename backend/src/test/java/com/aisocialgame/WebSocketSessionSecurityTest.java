package com.aisocialgame;

import com.aisocialgame.config.AppProperties;
import com.aisocialgame.config.WebSocketConfig;
import com.aisocialgame.model.User;
import com.aisocialgame.service.AuthService;
import com.aisocialgame.service.RoomAccessPolicy;
import com.aisocialgame.websocket.AuthenticatedSocketRegistry;
import com.aisocialgame.websocket.StompPrincipal;
import com.aisocialgame.websocket.WebSocketAuthChannelInterceptor;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.WebSocketSession;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WebSocketSessionSecurityTest {
    private static final class TestChannelRegistration extends ChannelRegistration {
        List<org.springframework.messaging.support.ChannelInterceptor> interceptorsForTest() { return getInterceptors(); }
    }
    private final AuthService auth = mock(AuthService.class);
    private final RoomAccessPolicy access = mock(RoomAccessPolicy.class);
    private final AuthenticatedSocketRegistry sockets = new AuthenticatedSocketRegistry(auth);
    private final WebSocketAuthChannelInterceptor inbound = new WebSocketAuthChannelInterceptor(auth, sockets, access);
    private final MessageChannel channel = mock(MessageChannel.class);

    private WebSocketSession connect(String id, String token) {
        var session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        sockets.opened(session);
        User user = new User(); user.setId("player");
        when(auth.authenticate(token)).thenReturn(user);
        when(auth.isSessionActive(token)).thenReturn(true);
        var headers = StompHeaderAccessor.create(StompCommand.CONNECT);
        headers.setSessionId(id); headers.setNativeHeader("Authorization", "Bearer " + token); headers.setLeaveMutable(true);
        inbound.preSend(MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders()), channel);
        return session;
    }

    private Message<byte[]> frame(StompCommand command, String destination) {
        var headers = StompHeaderAccessor.create(command);
        headers.setSessionId("socket"); headers.setUser(new StompPrincipal("player")); headers.setDestination(destination); headers.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
    }

    @Test void onlyExplicitClientRoutesAndOwnQueueAreAllowed() {
        try {
            for (String route : List.of("/topic/room/room1/state", "/queue/private", "/app/unknown", "/app/room/room1/chat/extra")) {
                var rejected = connect("socket", "token");
                assertThrows(IllegalArgumentException.class, () -> inbound.preSend(frame(StompCommand.SEND, route), channel));
                try { verify(rejected).close(any()); } catch (java.io.IOException ex) { fail(ex); }
            }
            for (String route : List.of("/user/other/queue/private", "/queue/private", "/topic/**", "/app/room/room1/chat")) {
                var rejected = connect("socket", "token");
                assertThrows(IllegalArgumentException.class, () -> inbound.preSend(frame(StompCommand.SUBSCRIBE, route), channel));
                try { verify(rejected).close(any()); } catch (java.io.IOException ex) { fail(ex); }
            }
            connect("socket", "token");
            assertNotNull(inbound.preSend(frame(StompCommand.SEND, "/app/room/room1/chat"), channel));
            verify(access).requireParticipant("room1", "player");
            assertNotNull(inbound.preSend(frame(StompCommand.SEND, "/app/room/room1/sync"), channel));
            assertNotNull(inbound.preSend(frame(StompCommand.SUBSCRIBE, "/user/queue/private"), channel));
            assertNotNull(inbound.preSend(frame(StompCommand.SUBSCRIBE, "/topic/room/room1/state"), channel));
            verify(access, times(2)).requireStateSubscription("room1", "player");
        } finally { sockets.stop(); }
    }

    @Test void logoutClosesEverySocketForThatTokenOnly() throws Exception {
        try {
            var first = connect("socket", "first"); var second = connect("socket2", "first"); var other = connect("other", "second");
            sockets.revoked(new AuthService.SessionRevoked("first"));
            verify(first).close(any()); verify(second).close(any()); verify(other, never()).close(any());
            assertFalse(sockets.active("socket")); assertTrue(sockets.active("other"));
        } finally { sockets.stop(); }
    }

    @Test void brokerMessagesRevalidateCurrentPermissionsAndSessionStore() {
        var configuration = new WebSocketConfig(inbound, new AppProperties(), sockets, access);
        try {
            connect("socket", "token");
            var registration = new TestChannelRegistration(); configuration.configureClientOutboundChannel(registration);
            var outbound = registration.interceptorsForTest().getFirst();
            var headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
            headers.setSessionId("socket"); headers.setDestination("/topic/room/room1/state");
            var message = MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
            assertNotNull(outbound.preSend(message, channel));
            doThrow(new IllegalStateException("permission changed")).when(access).requireRead("room1", "player");
            assertNull(outbound.preSend(message, channel));
            reset(access);
            when(auth.isSessionActive("token")).thenThrow(new IllegalStateException("Redis unavailable"));
            assertNull(outbound.preSend(message, channel));
        } finally { configuration.stopHeartbeat(); sockets.stop(); }
    }

    @Test void upstreamFailureClosesIdleSubscription() throws Exception {
        try {
            var connection = connect("socket", "token");
            when(auth.authenticate("token")).thenThrow(new IllegalStateException("deadline exceeded"));
            sockets.recheck();
            verify(connection, timeout(2000)).close(any());
        } finally { sockets.stop(); }
    }
}
