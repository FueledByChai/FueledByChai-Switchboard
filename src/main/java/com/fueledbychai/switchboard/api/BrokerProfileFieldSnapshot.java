package com.fueledbychai.switchboard.api;

public record BrokerProfileFieldSnapshot(
        String key,
        String label,
        boolean secret,
        boolean required,
        String value,
        boolean configured,
        String maskedValue,
        String helperText
) {
}
