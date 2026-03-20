package com.fueledbychai.switchboard.api;

import java.math.BigDecimal;

public record PositionSnapshot(
        String exchange,
        String exchangeLabel,
        String symbol,
        String exchangeSymbol,
        String side,
        BigDecimal size,
        BigDecimal averageCost,
        BigDecimal liquidationPrice,
        String status
) {
}
