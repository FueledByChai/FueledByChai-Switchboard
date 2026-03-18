package com.fueledbychai.switchboard.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

    @BeforeEach
    void resetProxyProperties() {
        PROPERTIES.forEach(System::clearProperty);
    }

    @AfterEach
    void clearProxyProperties() {
        PROPERTIES.forEach(System::clearProperty);
    }

    @Test
    void setsFueledByChaiAndSocksPropertiesWhenProxyIsEnabled() {
        SwitchboardProperties properties = new SwitchboardProperties();
        SwitchboardProperties.Proxy proxy = new SwitchboardProperties.Proxy();
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
        SwitchboardProperties properties = new SwitchboardProperties();

        new ProxyInitializer(properties).configureSocksProxy();

        assertNull(System.getProperty("fueledbychai.run.proxy"));
        assertNull(System.getProperty("socksProxyHost"));
        assertNull(System.getProperty("socksProxyPort"));
    }

    @Test
    void skipsProxyPropertiesWhenProxyConfigurationIsInvalid() {
        SwitchboardProperties properties = new SwitchboardProperties();
        SwitchboardProperties.Proxy proxy = new SwitchboardProperties.Proxy();
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
