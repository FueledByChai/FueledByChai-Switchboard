package com.fueledbychai.switchboard.model;

import java.util.Arrays;
import java.util.Locale;

public enum TimeInForce {
    GTC("GTC"),
    IOC("IOC"),
    POST_ONLY("Post Only");

    private final String displayName;

    TimeInForce(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public static TimeInForce fromInput(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Time in force is required.");
        }
        String normalized = value.trim().replace('-', '_').replace(' ', '_').toUpperCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(option -> option.name().equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported time in force: " + value));
    }
}
