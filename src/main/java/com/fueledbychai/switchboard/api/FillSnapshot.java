package com.fueledbychai.switchboard.api;

import java.math.BigDecimal;
import java.time.Instant;

public record FillSnapshot(
        String id,
        String exchange,
        String exchangeLabel,
        String symbol,
        String exchangeSymbol,
        String side,
        BigDecimal size,
        BigDecimal price,
        BigDecimal commission,
        String orderId,
        String clientOrderId,
        boolean taker,
        boolean snapshot,
        Instant time
) {
}
