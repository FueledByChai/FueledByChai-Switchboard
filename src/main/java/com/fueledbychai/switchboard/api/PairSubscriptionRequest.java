package com.fueledbychai.switchboard.api;

import jakarta.validation.constraints.NotBlank;

public record PairSubscriptionRequest(
        @NotBlank String leftMarketId,
        @NotBlank String rightMarketId
) {
}
