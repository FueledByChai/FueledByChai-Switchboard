package com.fueledbychai.switchboard.api;

import java.math.BigDecimal;
import java.time.Instant;

public record SnapshotQuoteResponse(
        String symbol,
        String exchangeSymbol,
        String exchange,
        String exchangeLabel,
        String assetType,
        String assetTypeLabel,
        BigDecimal bid,
        BigDecimal bidSize,
        BigDecimal ask,
        BigDecimal askSize,
        BigDecimal last,
        BigDecimal lastSize,
        BigDecimal volume,
        BigDecimal open,
        BigDecimal close,
        BigDecimal markPrice,
        Instant quoteTime,
        Instant requestedAt
) {
}
