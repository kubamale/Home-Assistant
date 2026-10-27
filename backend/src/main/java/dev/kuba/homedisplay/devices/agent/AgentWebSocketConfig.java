package dev.kuba.homedisplay.devices.agent;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/** Exposes the agent endpoint. Only a request carrying a valid agent token gets past the security filter. */
@Configuration
@EnableWebSocket
@ConditionalOnProperty(name = "app.devices.mode", havingValue = "agent")
public class AgentWebSocketConfig implements WebSocketConfigurer {

    private final AgentGateway gateway;

    public AgentWebSocketConfig(AgentGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(gateway, "/agent/ws");
    }

    /** Device lists are small; refuse anything large rather than buffer it. */
    @Bean
    ServletServerContainerFactoryBean webSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(64 * 1024);
        container.setMaxSessionIdleTimeout(0L);
        return container;
    }
}
