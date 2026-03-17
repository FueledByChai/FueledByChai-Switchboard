package com.fueledbychai.dashboard.api;

import java.math.BigDecimal;
import java.time.Instant;

public record PairSnapshot(
        String id,
        String leftMarketId,
        String rightMarketId,
        String leftLabel,
        String rightLabel,
        BigDecimal effectiveBid,
        BigDecimal effectiveAsk,
        BigDecimal buyLeftSellRightTakerSpread,
        BigDecimal buyLeftSellRightMakerTakerSpread,
        BigDecimal buyRightSellLeftTakerSpread,
        BigDecimal buyRightSellLeftMakerTakerSpread,
        BigDecimal buyLeftSellRightTakerBps,
        BigDecimal buyLeftSellRightMakerTakerBps,
        BigDecimal buyRightSellLeftTakerBps,
        BigDecimal buyRightSellLeftMakerTakerBps,
        BigDecimal fundingAnnualizedDiffPercent,
        BigDecimal fundingBpsPerHourDiff,
        Instant updatedAt
) {
}
