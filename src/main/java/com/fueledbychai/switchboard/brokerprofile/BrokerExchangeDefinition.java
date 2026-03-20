package com.fueledbychai.switchboard.brokerprofile;

import java.util.List;

public record BrokerExchangeDefinition(
        String exchange,
        String displayName,
        String accountLabelField,
        List<BrokerFieldDefinition> fields
) {
}
