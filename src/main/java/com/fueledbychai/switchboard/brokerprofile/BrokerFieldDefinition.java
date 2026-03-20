package com.fueledbychai.switchboard.brokerprofile;

public record BrokerFieldDefinition(
        String key,
        String label,
        boolean secret,
        boolean required,
        String helperText
) {
}
