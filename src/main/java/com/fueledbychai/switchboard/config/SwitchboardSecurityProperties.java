package com.fueledbychai.switchboard.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "switchboard.security")
public class SwitchboardSecurityProperties {

    private String bootstrapUsername = "admin";
    private String bootstrapPassword = "";

    public String getBootstrapUsername() {
        return bootstrapUsername;
    }

    public void setBootstrapUsername(String bootstrapUsername) {
        this.bootstrapUsername = bootstrapUsername == null ? "admin" : bootstrapUsername.trim();
    }

    public String getBootstrapPassword() {
        return bootstrapPassword;
    }

    public void setBootstrapPassword(String bootstrapPassword) {
        this.bootstrapPassword = bootstrapPassword == null ? "" : bootstrapPassword;
    }
}
