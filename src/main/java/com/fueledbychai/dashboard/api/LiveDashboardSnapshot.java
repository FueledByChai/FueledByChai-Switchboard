package com.fueledbychai.dashboard.api;

import java.time.Instant;
import java.util.List;

public record LiveDashboardSnapshot(
        Instant serverTime,
        List<MarketSnapshot> markets,
        List<PairSnapshot> pairs,
        List<OrderSnapshot> orders
) {
}
