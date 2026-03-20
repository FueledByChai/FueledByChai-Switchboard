package com.fueledbychai.switchboard.api;

import java.time.Instant;

public record BrokerConnectionSnapshot(
        String exchange,
        String exchangeLabel,
        boolean supported,
        boolean configured,
        boolean connected,
        String environment,
        String accountLabel,
        String status,
        String error,
        Instant updatedAt
) {
}
