package com.fueledbychai.switchboard.service;

import com.fueledbychai.switchboard.api.QuoteLatencySnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class QuoteLatencyTrackerTests {

    @Test
    void aggregatesRollingLatencyPercentilesByExchange() {
        QuoteLatencyTracker tracker = new QuoteLatencyTracker();
        Instant base = Instant.parse("2026-03-19T17:00:00Z");

        tracker.record("HYPERLIQUID", "Hyperliquid", base, base.plusMillis(10));
        tracker.record("HYPERLIQUID", "Hyperliquid", base, base.plusMillis(20));
        tracker.record("HYPERLIQUID", "Hyperliquid", base, base.plusMillis(30));
        tracker.record("HYPERLIQUID", "Hyperliquid", base, base.plusMillis(40));
        tracker.record("HYPERLIQUID", "Hyperliquid", base, base.plusMillis(50));
        tracker.record("PARADEX", "Paradex", base, base.plusMillis(12));

        List<QuoteLatencySnapshot> snapshots = tracker.snapshots();

        assertEquals(2, snapshots.size());
        QuoteLatencySnapshot hyperliquid = snapshots.stream()
                .filter(snapshot -> "HYPERLIQUID".equals(snapshot.exchange()))
                .findFirst()
                .orElseThrow();

        assertEquals(50L, hyperliquid.lastLatencyMs());
        assertEquals(30L, hyperliquid.p50LatencyMs());
        assertEquals(50L, hyperliquid.p95LatencyMs());
        assertEquals(5, hyperliquid.sampleCount());
    }

    @Test
    void clampsNegativeLatencyToZero() {
        QuoteLatencyTracker tracker = new QuoteLatencyTracker();
        Instant processedAt = Instant.parse("2026-03-19T17:00:00Z");
        Instant eventTime = processedAt.plusMillis(5);

        tracker.record("LIGHTER", "Lighter", eventTime, processedAt);

        QuoteLatencySnapshot snapshot = tracker.snapshots().getFirst();
        assertEquals(0L, snapshot.lastLatencyMs());
        assertEquals(0L, snapshot.p50LatencyMs());
        assertEquals(0L, snapshot.p95LatencyMs());
    }
}
