package com.fueledbychai.switchboard.config;

import com.fueledbychai.switchboard.ws.SwitchboardWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final SwitchboardWebSocketHandler switchboardWebSocketHandler;

    public WebSocketConfig(SwitchboardWebSocketHandler switchboardWebSocketHandler) {
        this.switchboardWebSocketHandler = switchboardWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(switchboardWebSocketHandler, "/ws/switchboard")
                .setAllowedOriginPatterns("*");
    }
}
