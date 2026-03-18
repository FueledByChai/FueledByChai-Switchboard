package com.fueledbychai.switchboard.api;

import java.math.BigDecimal;
import java.time.Instant;

public record OrderSnapshot(
        String id,
        String marketId,
        String symbol,
        String exchange,
        String exchangeSymbol,
        String assetType,
        String side,
        String orderType,
        String timeInForce,
        BigDecimal quantity,
        BigDecimal limitPrice,
        BigDecimal referencePrice,
        String status,
        String note,
        Instant createdAt,
        Instant updatedAt,
        Instant canceledAt
) {
}
