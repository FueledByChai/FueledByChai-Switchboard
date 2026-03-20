package com.fueledbychai.switchboard.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "switchboard.secrets")
public class SwitchboardSecretsProperties {

    private String masterKey = "";
    private String masterKeyPath = "./data/master.key";

    public String getMasterKey() {
        return masterKey;
    }

    public void setMasterKey(String masterKey) {
        this.masterKey = masterKey == null ? "" : masterKey.trim();
    }

    public String getMasterKeyPath() {
        return masterKeyPath;
    }

    public void setMasterKeyPath(String masterKeyPath) {
        this.masterKeyPath = masterKeyPath == null || masterKeyPath.isBlank()
                ? "./data/master.key"
                : masterKeyPath.trim();
    }
}
