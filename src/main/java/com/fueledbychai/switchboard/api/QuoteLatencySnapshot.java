package com.fueledbychai.switchboard.api;

import java.time.Instant;

public record QuoteLatencySnapshot(
        String exchange,
        String exchangeLabel,
        Long lastLatencyMs,
        Long p50LatencyMs,
        Long p95LatencyMs,
        Integer sampleCount,
        Instant lastEventTime,
        Instant lastProcessedAt
) {
}
