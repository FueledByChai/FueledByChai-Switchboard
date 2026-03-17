package com.fueledbychai.dashboard.model;

public enum OrderStatus {
    OPEN("Open"),
    CANCELED("Canceled");

    private final String displayName;

    OrderStatus(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
