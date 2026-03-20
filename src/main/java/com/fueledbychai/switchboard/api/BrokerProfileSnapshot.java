package com.fueledbychai.switchboard.api;

import java.time.Instant;
import java.util.List;

public record BrokerProfileSnapshot(
        String exchange,
        String displayName,
        String environment,
        String accountLabel,
        Instant updatedAt,
        List<BrokerProfileFieldSnapshot> fields
) {
}
