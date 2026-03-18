package com.fueledbychai.switchboard.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record SwitchboardSnapshot(
        Instant serverTime,
        List<MarketSnapshot> markets,
        List<PairSnapshot> pairs,
        List<OrderSnapshot> orders,
        Map<String, List<PriceHistoryPoint>> history
) {
}
