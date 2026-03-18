package com.fueledbychai.switchboard.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fueledbychai.switchboard.api.LiveSwitchboardSnapshot;
import com.fueledbychai.switchboard.ws.SwitchboardWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class SwitchboardBroadcastService {

    private static final Logger log = LoggerFactory.getLogger(SwitchboardBroadcastService.class);

    private final SwitchboardStateService switchboardStateService;
    private final SwitchboardWebSocketHandler switchboardWebSocketHandler;
    private final ObjectMapper objectMapper;

    public SwitchboardBroadcastService(SwitchboardStateService switchboardStateService,
                                       SwitchboardWebSocketHandler switchboardWebSocketHandler,
                                       ObjectMapper objectMapper) {
        this.switchboardStateService = switchboardStateService;
        this.switchboardWebSocketHandler = switchboardWebSocketHandler;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedRateString = "${switchboard.broadcast-interval-ms:100}")
    public void broadcastSwitchboard() {
        if (!switchboardWebSocketHandler.hasSessions()) {
            return;
        }
        LiveSwitchboardSnapshot snapshot = switchboardStateService.liveSnapshot();
        try {
            String payload = objectMapper.writeValueAsString(snapshot);
            switchboardWebSocketHandler.broadcast(payload);
        } catch (JsonProcessingException e) {
            log.warn("Unable to serialize switchboard snapshot", e);
        }
    }
}
