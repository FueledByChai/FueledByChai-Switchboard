package com.fueledbychai.switchboard.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "switchboard")
public class SwitchboardProperties {

    private int broadcastIntervalMs = 100;
    private int historyMinutes = 10;
    private int maxRows = 50;
    private Proxy proxy = new Proxy();

    public int getBroadcastIntervalMs() {
        return broadcastIntervalMs;
    }

    public void setBroadcastIntervalMs(int broadcastIntervalMs) {
        this.broadcastIntervalMs = Math.max(50, broadcastIntervalMs);
    }

    public int getHistoryMinutes() {
        return historyMinutes;
    }

    public void setHistoryMinutes(int historyMinutes) {
        this.historyMinutes = Math.max(1, historyMinutes);
    }

    public int getMaxRows() {
        return maxRows;
    }

    public void setMaxRows(int maxRows) {
        this.maxRows = Math.max(1, maxRows);
    }

    public Proxy getProxy() {
        return proxy;
    }

    public void setProxy(Proxy proxy) {
        this.proxy = proxy;
    }

    public static class Proxy {
        private boolean enabled;
        private String host = "";
        private int port = 1080;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host == null ? "" : host;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }
    }
}
