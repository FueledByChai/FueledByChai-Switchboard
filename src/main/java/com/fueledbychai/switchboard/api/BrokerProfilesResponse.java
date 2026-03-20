package com.fueledbychai.switchboard.api;

import java.util.List;

public record BrokerProfilesResponse(
        List<BrokerExchangeDefinitionSnapshot> definitions,
        List<BrokerProfileSnapshot> profiles
) {
}
