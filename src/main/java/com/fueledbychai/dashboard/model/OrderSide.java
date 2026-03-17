package com.fueledbychai.dashboard.model;

import java.util.Arrays;
import java.util.Locale;

public enum OrderSide {
    BUY("Buy"),
    SELL("Sell");

    private final String displayName;

    OrderSide(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public static OrderSide fromInput(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Order side is required.");
        }
        String normalized = value.trim().replace('-', '_').replace(' ', '_').toUpperCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(option -> option.name().equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported order side: " + value));
    }
}
