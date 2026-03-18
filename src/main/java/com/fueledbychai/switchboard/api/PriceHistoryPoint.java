package com.fueledbychai.switchboard.api;

import java.math.BigDecimal;
import java.time.Instant;

public record PriceHistoryPoint(
        Instant time,
        BigDecimal value
) {
}
