package com.fueledbychai.switchboard.api;

import java.time.Instant;
import java.util.List;

public record LiveSwitchboardSnapshot(
        Instant serverTime,
        List<MarketSnapshot> markets,
        List<PairSnapshot> pairs,
        List<OrderSnapshot> orders
) {
}
