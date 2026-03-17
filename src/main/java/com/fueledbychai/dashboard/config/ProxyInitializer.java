package com.fueledbychai.dashboard.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ProxyInitializer {

    private static final Logger log = LoggerFactory.getLogger(ProxyInitializer.class);
    private final DashboardProperties properties;

    public ProxyInitializer(DashboardProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void configureSocksProxy() {
        DashboardProperties.Proxy proxy = properties.getProxy();
        if (!proxy.isEnabled()) {
            return;
        }

        if (proxy.getHost().isBlank() || proxy.getPort() <= 0) {
            log.warn("SOCKS proxy enabled but host/port are invalid. Ignoring proxy settings.");
            return;
        }

        System.setProperty("socksProxyHost", proxy.getHost());
        System.setProperty("socksProxyPort", Integer.toString(proxy.getPort()));
        log.info("Configured JVM SOCKS proxy {}:{}", proxy.getHost(), proxy.getPort());
    }
}
