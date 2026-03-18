package com.fueledbychai.switchboard.api;

import java.math.BigDecimal;
import java.time.Instant;

public record MarketSnapshot(
        String id,
        Integer rowIndex,
        String symbol,
        String exchangeSymbol,
        String exchange,
        String assetType,
        BigDecimal bid,
        BigDecimal ask,
        BigDecimal last,
        BigDecimal volume,
        BigDecimal fundingRateApr,
        BigDecimal fundingRateBpsPerHour,
        Instant updatedAt
) {
}
