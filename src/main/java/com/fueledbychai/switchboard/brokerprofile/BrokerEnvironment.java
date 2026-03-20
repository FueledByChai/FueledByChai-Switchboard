package com.fueledbychai.switchboard.brokerprofile;

import java.util.Locale;

public enum BrokerEnvironment {
    MAINNET,
    TESTNET;

    public static BrokerEnvironment fromInput(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Environment is required.");
        }
        String normalized = value.trim().replace('-', '_').replace(' ', '_').toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "MAINNET", "MAIN", "PROD", "PRODUCTION" -> MAINNET;
            case "TESTNET", "TEST", "PAPER", "SANDBOX" -> TESTNET;
            default -> throw new IllegalArgumentException("Unsupported environment: " + value);
        };
    }
}
