package com.fueledbychai.switchboard.api;

import java.util.List;

public record BrokerExchangeDefinitionSnapshot(
        String exchange,
        String displayName,
        List<BrokerProfileFieldSnapshot> fields
) {
}
