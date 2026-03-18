package com.fueledbychai.switchboard.model;

import com.fueledbychai.data.InstrumentType;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public record SupportedAssetType(
        String name,
        String displayName,
        InstrumentType instrumentType
) {

    private static final Map<InstrumentType, SupportedAssetType> TYPES_BY_INSTRUMENT = new EnumMap<>(InstrumentType.class);
    private static final Map<String, InstrumentType> INSTRUMENT_BY_ALIAS = Map.ofEntries(
            Map.entry("SPOT", InstrumentType.CRYPTO_SPOT),
            Map.entry("CRYPTO_SPOT", InstrumentType.CRYPTO_SPOT),
            Map.entry("PERP", InstrumentType.PERPETUAL_FUTURES),
            Map.entry("PERPETUAL", InstrumentType.PERPETUAL_FUTURES),
            Map.entry("PERPETUAL_FUTURES", InstrumentType.PERPETUAL_FUTURES),
            Map.entry("FUTURE", InstrumentType.FUTURES),
            Map.entry("FUTURES", InstrumentType.FUTURES),
            Map.entry("OPTION", InstrumentType.OPTION),
            Map.entry("OPTIONS", InstrumentType.OPTION),
            Map.entry("PERP_OPTION", InstrumentType.PERPETUAL_OPTION),
            Map.entry("PERPETUAL_OPTION", InstrumentType.PERPETUAL_OPTION)
    );

    public SupportedAssetType {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Asset type name is required.");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("Asset type displayName is required.");
        }
        instrumentType = Objects.requireNonNull(instrumentType, "instrumentType is required");
        if (instrumentType == InstrumentType.NONE) {
            throw new IllegalArgumentException("InstrumentType.NONE is not a supported market-data asset type.");
        }
    }

    public static SupportedAssetType fromInput(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Asset type is required.");
        }

        String normalized = normalizeToken(value);
        InstrumentType aliased = INSTRUMENT_BY_ALIAS.get(normalized);
        if (aliased != null) {
            return fromInstrumentType(aliased);
        }

        try {
            return fromInstrumentType(InstrumentType.valueOf(normalized));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unsupported asset type: " + value, e);
        }
    }

    public static SupportedAssetType fromInstrumentType(InstrumentType instrumentType) {
        Objects.requireNonNull(instrumentType, "instrumentType is required");
        if (instrumentType == InstrumentType.NONE) {
            throw new IllegalArgumentException("InstrumentType.NONE is not supported.");
        }
        synchronized (TYPES_BY_INSTRUMENT) {
            return TYPES_BY_INSTRUMENT.computeIfAbsent(instrumentType, SupportedAssetType::create);
        }
    }

    private static SupportedAssetType create(InstrumentType instrumentType) {
        return switch (instrumentType) {
            case CRYPTO_SPOT -> new SupportedAssetType("SPOT", "Spot", instrumentType);
            case PERPETUAL_FUTURES -> new SupportedAssetType("PERP", "Perp", instrumentType);
            case PERPETUAL_OPTION -> new SupportedAssetType("PERP_OPTION", "Perp Option", instrumentType);
            default -> new SupportedAssetType(instrumentType.name(), humanize(instrumentType.name()), instrumentType);
        };
    }

    private static String normalizeToken(String value) {
        return value.trim()
                .replace('-', '_')
                .replace(' ', '_')
                .toUpperCase(Locale.ROOT);
    }

    private static String humanize(String value) {
        String[] tokens = value.toLowerCase(Locale.ROOT).split("_");
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < tokens.length; index++) {
            String token = tokens[index];
            if (token.isBlank()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(token.charAt(0)));
            if (token.length() > 1) {
                builder.append(token.substring(1));
            }
        }
        return builder.toString();
    }
}
