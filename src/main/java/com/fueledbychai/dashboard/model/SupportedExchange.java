package com.fueledbychai.dashboard.model;

import com.fueledbychai.data.Exchange;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

public enum SupportedExchange {
    LIGHTER("Lighter", Exchange.LIGHTER, EnumSet.of(SupportedAssetType.PERP, SupportedAssetType.SPOT)),
    HYPERLIQUID("Hyperliquid", Exchange.HYPERLIQUID, EnumSet.of(SupportedAssetType.PERP)),
    PARADEX("Paradex", Exchange.PARADEX, EnumSet.of(SupportedAssetType.PERP, SupportedAssetType.SPOT)),
    BINANCE_SPOT("Binance Spot", Exchange.BINANCE_SPOT, EnumSet.of(SupportedAssetType.SPOT));

    private final String displayName;
    private final Exchange exchange;
    private final Set<SupportedAssetType> supportedAssetTypes;

    SupportedExchange(String displayName, Exchange exchange, Set<SupportedAssetType> supportedAssetTypes) {
        this.displayName = displayName;
        this.exchange = exchange;
        this.supportedAssetTypes = Set.copyOf(supportedAssetTypes);
    }

    public String displayName() {
        return displayName;
    }

    public Exchange exchange() {
        return exchange;
    }

    public Set<SupportedAssetType> supportedAssetTypes() {
        return supportedAssetTypes;
    }

    public boolean supports(SupportedAssetType assetType) {
        return supportedAssetTypes.contains(assetType);
    }

    public static SupportedExchange fromInput(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Exchange is required.");
        }
        String normalized = value.trim().replace('-', '_').replace(' ', '_').toUpperCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(exchangeOption -> exchangeOption.name().equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported exchange: " + value));
    }
}
