package com.fueledbychai.dashboard.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ProxyInitializerTests {

    private static final List<String> PROPERTIES = List.of(
            "fueledbychai.run.proxy",
            "socksProxyHost",
            "socksProxyPort"
    );

    @AfterEach
    void clearProxyProperties() {
        PROPERTIES.forEach(System::clearProperty);
    }

    @Test
    void setsFueledByChaiAndSocksPropertiesWhenProxyIsEnabled() {
        DashboardProperties properties = new DashboardProperties();
        DashboardProperties.Proxy proxy = new DashboardProperties.Proxy();
        proxy.setEnabled(true);
        proxy.setHost("localhost");
        proxy.setPort(1080);
        properties.setProxy(proxy);

        new ProxyInitializer(properties).configureSocksProxy();

        assertEquals("true", System.getProperty("fueledbychai.run.proxy"));
        assertEquals("localhost", System.getProperty("socksProxyHost"));
        assertEquals("1080", System.getProperty("socksProxyPort"));
    }

    @Test
    void skipsProxyPropertiesWhenProxyIsDisabled() {
        DashboardProperties properties = new DashboardProperties();

        new ProxyInitializer(properties).configureSocksProxy();

        assertNull(System.getProperty("fueledbychai.run.proxy"));
        assertNull(System.getProperty("socksProxyHost"));
        assertNull(System.getProperty("socksProxyPort"));
    }

    @Test
    void skipsProxyPropertiesWhenProxyConfigurationIsInvalid() {
        DashboardProperties properties = new DashboardProperties();
        DashboardProperties.Proxy proxy = new DashboardProperties.Proxy();
        proxy.setEnabled(true);
        proxy.setHost("");
        proxy.setPort(0);
        properties.setProxy(proxy);

        new ProxyInitializer(properties).configureSocksProxy();

        assertNull(System.getProperty("fueledbychai.run.proxy"));
        assertNull(System.getProperty("socksProxyHost"));
        assertNull(System.getProperty("socksProxyPort"));
    }
}
