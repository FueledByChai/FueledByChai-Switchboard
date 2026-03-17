package com.fueledbychai.dashboard.model;

import com.fueledbychai.data.InstrumentType;

import java.util.Locale;

public enum SupportedAssetType {
    PERP("Perp", InstrumentType.PERPETUAL_FUTURES),
    SPOT("Spot", InstrumentType.CRYPTO_SPOT);

    private final String displayName;
    private final InstrumentType instrumentType;

    SupportedAssetType(String displayName, InstrumentType instrumentType) {
        this.displayName = displayName;
        this.instrumentType = instrumentType;
    }

    public String displayName() {
        return displayName;
    }

    public InstrumentType instrumentType() {
        return instrumentType;
    }

    public static SupportedAssetType fromInput(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Asset type is required.");
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "perp", "perpetual", "perpetual_futures", "perpetual-futures" -> PERP;
            case "spot", "crypto_spot", "crypto-spot" -> SPOT;
            default -> throw new IllegalArgumentException("Unsupported asset type: " + value);
        };
    }
}
