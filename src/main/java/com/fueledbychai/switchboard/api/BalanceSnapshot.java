package com.fueledbychai.switchboard.api;

import java.math.BigDecimal;
import java.time.Instant;

public record BalanceSnapshot(
        String exchange,
        String exchangeLabel,
        BigDecimal equity,
        BigDecimal availableFunds,
        Instant updatedAt
) {
}
