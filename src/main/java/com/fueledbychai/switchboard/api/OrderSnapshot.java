package com.fueledbychai.switchboard.api;

import java.math.BigDecimal;
import java.time.Instant;

public record OrderSnapshot(
        String id,
        String marketId,
        String symbol,
        String exchange,
        String exchangeLabel,
        String exchangeSymbol,
        String assetType,
        String side,
        String orderType,
        String timeInForce,
        String exchangeOrderId,
        String clientOrderId,
        BigDecimal quantity,
        BigDecimal filledQuantity,
        BigDecimal remainingQuantity,
        BigDecimal limitPrice,
        BigDecimal averageFillPrice,
        String status,
        String cancelReason,
        String note,
        Instant createdAt,
        Instant updatedAt,
        Instant canceledAt,
        Instant filledAt
) {
}
