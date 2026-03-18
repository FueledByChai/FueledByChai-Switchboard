package com.fueledbychai.switchboard.model;

import java.util.Arrays;
import java.util.Locale;

public enum OrderType {
    LIMIT("Limit"),
    MARKET("Market");

    private final String displayName;

    OrderType(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public static OrderType fromInput(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Order type is required.");
        }
        String normalized = value.trim().replace('-', '_').replace(' ', '_').toUpperCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(option -> option.name().equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported order type: " + value));
    }
}
