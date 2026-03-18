package com.fueledbychai.switchboard.model;

import com.fueledbychai.data.Exchange;

import java.util.List;
import java.util.Objects;

public record SupportedExchange(
        String name,
        String displayName,
        Exchange exchange,
        List<SupportedAssetType> supportedAssetTypes
) {

    public SupportedExchange {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Exchange name is required.");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("Exchange displayName is required.");
        }
        exchange = Objects.requireNonNull(exchange, "exchange is required");
        supportedAssetTypes = List.copyOf(Objects.requireNonNull(supportedAssetTypes, "supportedAssetTypes is required"));
    }

    public boolean supports(SupportedAssetType assetType) {
        return supportedAssetTypes.contains(assetType);
    }
}
