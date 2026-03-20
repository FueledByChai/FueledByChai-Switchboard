package com.fueledbychai.switchboard.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record OrderModifyRequest(
        @NotBlank String side,
        @NotBlank String orderType,
        @NotBlank String timeInForce,
        String clientOrderId,
        @NotNull @Positive BigDecimal quantity,
        @Positive BigDecimal limitPrice
) {
}
