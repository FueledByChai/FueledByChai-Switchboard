package com.fueledbychai.switchboard.api;

public record InstrumentLookupOption(
        String symbol,
        String exchangeSymbol,
        String exchange,
        String exchangeLabel,
        String assetType,
        String assetTypeLabel,
        String description
) {
}
