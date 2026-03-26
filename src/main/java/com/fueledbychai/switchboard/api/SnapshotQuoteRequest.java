package com.fueledbychai.switchboard.api;

import jakarta.validation.constraints.NotBlank;

public record SnapshotQuoteRequest(
        @NotBlank String symbol,
        @NotBlank String exchange,
        @NotBlank String assetType
) {
}
