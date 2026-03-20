package com.fueledbychai.switchboard.service;

import com.fueledbychai.switchboard.api.QuoteLatencySnapshot;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

final class QuoteLatencyTracker {

    private static final int MAX_SAMPLES_PER_EXCHANGE = 4096;

    private final ConcurrentMap<String, ExchangeLatencyWindow> windowsByExchange = new ConcurrentHashMap<>();

    void record(String exchangeName, String exchangeLabel, Instant eventTime, Instant processedAt) {
        if (exchangeName == null || exchangeName.isBlank() || eventTime == null || processedAt == null) {
            return;
        }

        long latencyMs = Math.max(0L, Duration.between(eventTime, processedAt).toMillis());
        ExchangeLatencyWindow window = windowsByExchange.computeIfAbsent(exchangeName,
                ignored -> new ExchangeLatencyWindow(exchangeName, exchangeLabel));
        window.record(exchangeLabel, latencyMs, eventTime, processedAt);
    }

    List<QuoteLatencySnapshot> snapshots() {
        return windowsByExchange.values().stream()
                .map(ExchangeLatencyWindow::toSnapshot)
                .sorted(Comparator.comparing(snapshot -> String.valueOf(snapshot.exchangeLabel())))
                .toList();
    }

    static final class ExchangeLatencyWindow {
        private final String exchangeName;
        private final long[] recentLatencies = new long[MAX_SAMPLES_PER_EXCHANGE];

        private String exchangeLabel;
        private int size;
        private int nextIndex;
        private long lastLatencyMs;
        private Instant lastEventTime;
        private Instant lastProcessedAt;

        private ExchangeLatencyWindow(String exchangeName, String exchangeLabel) {
            this.exchangeName = exchangeName;
            this.exchangeLabel = exchangeLabel;
        }

        private synchronized void record(String exchangeLabel, long latencyMs, Instant eventTime, Instant processedAt) {
            this.exchangeLabel = exchangeLabel;
            recentLatencies[nextIndex] = latencyMs;
            nextIndex = (nextIndex + 1) % recentLatencies.length;
            if (size < recentLatencies.length) {
                size += 1;
            }
            lastLatencyMs = latencyMs;
            lastEventTime = eventTime;
            lastProcessedAt = processedAt;
        }

        private synchronized QuoteLatencySnapshot toSnapshot() {
            long[] sample = Arrays.copyOf(recentLatencies, size);
            Arrays.sort(sample);
            return new QuoteLatencySnapshot(
                    exchangeName,
                    exchangeLabel == null || exchangeLabel.isBlank() ? exchangeName : exchangeLabel,
                    size == 0 ? null : lastLatencyMs,
                    percentile(sample, 0.50),
                    percentile(sample, 0.95),
                    size,
                    lastEventTime,
                    lastProcessedAt
            );
        }

        private static Long percentile(long[] sortedSample, double percentile) {
            if (sortedSample == null || sortedSample.length == 0) {
                return null;
            }
            int index = (int) Math.ceil(percentile * sortedSample.length) - 1;
            int boundedIndex = Math.max(0, Math.min(sortedSample.length - 1, index));
            return sortedSample[boundedIndex];
        }
    }
}
