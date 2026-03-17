package com.fueledbychai.dashboard.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fueledbychai.dashboard.api.LiveDashboardSnapshot;
import com.fueledbychai.dashboard.ws.DashboardWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class DashboardBroadcastService {

    private static final Logger log = LoggerFactory.getLogger(DashboardBroadcastService.class);

    private final DashboardStateService dashboardStateService;
    private final DashboardWebSocketHandler dashboardWebSocketHandler;
    private final ObjectMapper objectMapper;

    public DashboardBroadcastService(DashboardStateService dashboardStateService,
                                     DashboardWebSocketHandler dashboardWebSocketHandler,
                                     ObjectMapper objectMapper) {
        this.dashboardStateService = dashboardStateService;
        this.dashboardWebSocketHandler = dashboardWebSocketHandler;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedRateString = "${dashboard.broadcast-interval-ms:100}")
    public void broadcastDashboard() {
        if (!dashboardWebSocketHandler.hasSessions()) {
            return;
        }
        LiveDashboardSnapshot snapshot = dashboardStateService.liveSnapshot();
        try {
            String payload = objectMapper.writeValueAsString(snapshot);
            dashboardWebSocketHandler.broadcast(payload);
        } catch (JsonProcessingException e) {
            log.warn("Unable to serialize dashboard snapshot", e);
        }
    }
}
