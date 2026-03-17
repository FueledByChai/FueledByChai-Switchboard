package com.fueledbychai.dashboard.api;

import java.math.BigDecimal;
import java.time.Instant;

public record PriceHistoryPoint(
        Instant time,
        BigDecimal value
) {
}
