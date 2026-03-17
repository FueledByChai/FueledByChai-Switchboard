package com.fueledbychai.dashboard.api;

import jakarta.validation.constraints.NotBlank;

public record PairSubscriptionRequest(
        @NotBlank String leftMarketId,
        @NotBlank String rightMarketId
) {
}
