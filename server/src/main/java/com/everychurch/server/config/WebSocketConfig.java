package com.everychurch.server.config;

import com.everychurch.server.security.StompAuthChannelInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP over WebSocket for the per-church group chat (docs/ARCHITECTURE.md "Group chat").
 * An in-memory simple broker is enough at this stage — there's a single server process
 * and no need for RabbitMQ/ActiveMQ until the chat has to fan out across several.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final StompAuthChannelInterceptor stompAuthChannelInterceptor;
    private final String clientBaseUrl;

    public WebSocketConfig(
        StompAuthChannelInterceptor stompAuthChannelInterceptor,
        @Value("${app.client-base-url:http://localhost:4200}") String clientBaseUrl
    ) {
        this.stompAuthChannelInterceptor = stompAuthChannelInterceptor;
        this.clientBaseUrl = clientBaseUrl;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // SockJS gives browsers a fallback where raw WebSocket is blocked; the raw
        // WebSocket transport is still served at /ws/websocket. Origins are limited to
        // the Angular app — a browser on any other site can't open the socket.
        registry.addEndpoint("/ws")
            .setAllowedOriginPatterns(clientBaseUrl)
            .withSockJS();
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.setApplicationDestinationPrefixes("/app");
        registry.enableSimpleBroker("/topic");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(stompAuthChannelInterceptor);
    }
}
