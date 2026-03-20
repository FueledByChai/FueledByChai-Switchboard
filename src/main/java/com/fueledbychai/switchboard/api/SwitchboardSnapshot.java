package com.fueledbychai.switchboard.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record SwitchboardSnapshot(
        Instant serverTime,
        List<MarketSnapshot> markets,
        List<PairSnapshot> pairs,
        List<QuoteLatencySnapshot> quoteLatencies,
        List<OrderSnapshot> orders,
        List<BrokerConnectionSnapshot> brokerConnections,
        List<BalanceSnapshot> balances,
        List<PositionSnapshot> positions,
        List<FillSnapshot> fills,
        Map<String, List<PriceHistoryPoint>> history
) {
}
