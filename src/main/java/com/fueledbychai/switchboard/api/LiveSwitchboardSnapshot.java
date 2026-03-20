package com.fueledbychai.switchboard.api;

import java.time.Instant;
import java.util.List;

public record LiveSwitchboardSnapshot(
        Instant serverTime,
        List<MarketSnapshot> markets,
        List<PairSnapshot> pairs,
        List<QuoteLatencySnapshot> quoteLatencies,
        List<OrderSnapshot> orders,
        List<BrokerConnectionSnapshot> brokerConnections,
        List<BalanceSnapshot> balances,
        List<PositionSnapshot> positions,
        List<FillSnapshot> fills
) {
}
