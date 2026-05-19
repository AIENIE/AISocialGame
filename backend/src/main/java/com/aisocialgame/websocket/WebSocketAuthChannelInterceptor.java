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

    public WebSocketAuthChannelInterceptor(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public Message<?> preSend(@NonNull Message<?> message, @NonNull MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || !StompCommand.CONNECT.equals(accessor.getCommand())) {
            return message;
        }

        String token = sanitizeToken(accessor.getFirstNativeHeader("Authorization"));
        String resolvedPlayerId = resolvePlayerId(token);
        if (!StringUtils.hasText(resolvedPlayerId)) {
            throw new IllegalArgumentException("WebSocket 连接需要登录");
        }
        accessor.setUser(new StompPrincipal(resolvedPlayerId));
        return message;
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
