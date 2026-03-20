package com.fueledbychai.switchboard.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

public record BrokerProfileUpsertRequest(
        @NotBlank String environment,
        @NotNull Map<String, String> fields
) {
}
