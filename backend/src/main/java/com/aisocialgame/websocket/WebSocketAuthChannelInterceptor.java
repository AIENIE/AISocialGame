package com.aisocialgame.websocket;

import com.aisocialgame.model.User;
import com.aisocialgame.service.AuthService;
import org.springframework.lang.NonNull;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class WebSocketAuthChannelInterceptor implements ChannelInterceptor {
    private final AuthService authService;
    private final AuthenticatedSocketRegistry sockets;
    private final com.aisocialgame.service.RoomAccessPolicy access;

    public WebSocketAuthChannelInterceptor(AuthService authService, AuthenticatedSocketRegistry sockets, com.aisocialgame.service.RoomAccessPolicy access) {
        this.sockets = sockets; this.access = access;
        this.authService = authService;
    }

    @Override
    public Message<?> preSend(@NonNull Message<?> message, @NonNull MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) throw new IllegalArgumentException("Invalid STOMP frame");
        StompCommand command = accessor.getCommand();
        if (command == StompCommand.DISCONNECT) return message;
        try {
            if (command == StompCommand.CONNECT || command == StompCommand.STOMP) {
                String token = sanitizeToken(accessor.getFirstNativeHeader("Authorization"));
                String playerId = resolvePlayerId(token);
                if (!StringUtils.hasText(playerId)) throw new IllegalArgumentException("WebSocket requires authentication");
                sockets.bind(accessor.getSessionId(), token, playerId);
                accessor.setUser(new StompPrincipal(playerId));
                return message;
            }
            if (!sockets.active(accessor.getSessionId()) || accessor.getUser() == null) throw new IllegalArgumentException("Session expired or revoked");
            String playerId = accessor.getUser().getName();
            String destination = accessor.getDestination();
            if (command == StompCommand.SEND) {
                var route = java.util.regex.Pattern.compile("^/app/room/([A-Za-z0-9_-]{1,64})/(chat|sync)$").matcher(destination == null ? "" : destination);
                if (!route.matches()) throw new IllegalArgumentException("Client destination denied");
                if ("chat".equals(route.group(2))) access.requireParticipant(route.group(1), playerId);
                else access.requireStateSubscription(route.group(1), playerId);
            } else if (command == StompCommand.SUBSCRIBE) {
                if (!"/user/queue/private".equals(destination)) {
                    var route = java.util.regex.Pattern.compile("^/topic/room/([A-Za-z0-9_-]{1,64})/(state|seat|chat)$").matcher(destination == null ? "" : destination);
                    if (!route.matches()) throw new IllegalArgumentException("Subscription denied");
                    if ("state".equals(route.group(2))) access.requireStateSubscription(route.group(1), playerId);
                    else access.requireLobbyRead(route.group(1), playerId);
                }
            } else if (command != null && command != StompCommand.UNSUBSCRIBE) {
                throw new IllegalArgumentException("STOMP command denied");
            }
            return message;
        } catch (RuntimeException denied) {
            sockets.reject(accessor.getSessionId());
            throw denied;
        }
    }

    private String resolvePlayerId(String token) {
        if (StringUtils.hasText(token)) {
            User user = authService.authenticate(token);
            if (user != null) {
                return user.getId();
            }
        }
        return null;
    }

    private String sanitizeToken(String raw) {
        String val = trim(raw);
        if (!StringUtils.hasText(val)) {
            return null;
        }
        if (val.toLowerCase().startsWith("bearer ")) {
            return trim(val.substring(7));
        }
        return val;
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }
}
