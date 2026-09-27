package com.aisocialgame.config;

import com.aisocialgame.websocket.WebSocketAuthChannelInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {
    private final WebSocketAuthChannelInterceptor authChannelInterceptor;
    private final AppProperties appProperties;
    private final com.aisocialgame.service.RoomAccessPolicy access;
    private final com.aisocialgame.websocket.AuthenticatedSocketRegistry sockets;
    private final org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler heartbeat = heartbeatScheduler();

    public WebSocketConfig(WebSocketAuthChannelInterceptor authChannelInterceptor, AppProperties appProperties, com.aisocialgame.websocket.AuthenticatedSocketRegistry sockets, com.aisocialgame.service.RoomAccessPolicy access) {
        this.access = access;
        this.sockets = sockets;
        this.authChannelInterceptor = authChannelInterceptor;
        this.appProperties = appProperties;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue").setHeartbeatValue(new long[]{10000, 10000}).setTaskScheduler(heartbeat);
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
        registry.setPreservePublishOrder(true);
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.setPreserveReceiveOrder(true);
        String[] origins = appProperties.getCors().getAllowedOrigins().toArray(String[]::new);
        registry.addEndpoint("/ws").setAllowedOrigins(origins);
        registry.addEndpoint("/ws").setAllowedOrigins(origins).withSockJS();
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(authChannelInterceptor);
    }
    private static org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler heartbeatScheduler() {
        var scheduler = new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1); scheduler.setThreadNamePrefix("stomp-heartbeat-"); scheduler.initialize();
        return scheduler;
    }

    @jakarta.annotation.PreDestroy public void stopHeartbeat() { heartbeat.shutdown(); }

    @Override
    public void configureWebSocketTransport(org.springframework.web.socket.config.annotation.WebSocketTransportRegistration registration) {
        registration.addDecoratorFactory(handler -> new org.springframework.web.socket.handler.WebSocketHandlerDecorator(handler) {
            @Override public void afterConnectionEstablished(org.springframework.web.socket.WebSocketSession session) throws Exception {
                sockets.opened(session);
                super.afterConnectionEstablished(session);
            }
            @Override public void afterConnectionClosed(org.springframework.web.socket.WebSocketSession session, org.springframework.web.socket.CloseStatus status) throws Exception {
                try { super.afterConnectionClosed(session, status); } finally { sockets.closed(session.getId()); }
            }
        });
    }

    @Override
    public void configureClientOutboundChannel(ChannelRegistration registration) {
        registration.interceptors(new org.springframework.messaging.support.ChannelInterceptor() {
            @Override public org.springframework.messaging.Message<?> preSend(org.springframework.messaging.Message<?> message, org.springframework.messaging.MessageChannel channel) {
                var accessor = org.springframework.messaging.simp.stomp.StompHeaderAccessor.wrap(message);
                if (accessor.getMessageType() == org.springframework.messaging.simp.SimpMessageType.MESSAGE) {
                    if (!sockets.active(accessor.getSessionId())) return null;
                    String destination = accessor.getDestination();
                    if (destination != null && destination.startsWith("/topic/room/")) {
                        var route = java.util.regex.Pattern.compile("^/topic/room/([A-Za-z0-9_-]{1,64})/(state|seat|chat)$").matcher(destination);
                        if (!route.matches()) return null;
                        try {
                            if ("state".equals(route.group(2))) access.requireRead(route.group(1), sockets.playerId(accessor.getSessionId()));
                            else access.requireLobbyRead(route.group(1), sockets.playerId(accessor.getSessionId()));
                        }
                        catch (RuntimeException denied) { return null; }
                    }
                }
                return message;
            }
        });
    }

}
