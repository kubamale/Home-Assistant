package dev.kuba.homedisplay.voice;

import java.util.Map;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;

import dev.kuba.homedisplay.security.CredentialPrincipal;

/**
 * The microphone endpoint. The security filter has already authenticated the upgrade request (a phone or a door
 * screen, by its cookie); the interceptor carries who it was into the session. Only pages from the same origin may
 * connect: Spring's default, because no allowed origins are listed.
 */
@Configuration
@EnableWebSocket
class VoiceWebSocketConfig implements WebSocketConfigurer {

    private final VoiceSessionHandler handler;

    VoiceWebSocketConfig(VoiceSessionHandler handler) {
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/api/voice/session").addInterceptors(new HandshakeInterceptor() {
            @Override
            public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler,
                    Map<String, Object> attributes) {
                Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                if (auth != null && auth.getPrincipal() instanceof CredentialPrincipal who) {
                    attributes.put(VoiceSessionHandler.PRINCIPAL, who);
                    return true;
                }
                return false;
            }

            @Override
            public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler,
                    Exception exception) {
            }
        });
    }
}
