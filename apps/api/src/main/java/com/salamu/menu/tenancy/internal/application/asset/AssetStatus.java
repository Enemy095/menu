package com.salamu.menu.tenancy.internal.application.asset;

public enum AssetStatus {
    PENDING("pending"),
    ACTIVE("active"),
    DELETED("deleted");

    private final String databaseValue;

    AssetStatus(String databaseValue) {
        this.databaseValue = databaseValue;
    }

    public String databaseValue() {
        return databaseValue;
    }

    public static AssetStatus fromDatabase(String value) {
        for (AssetStatus status : values()) {
            if (status.databaseValue.equals(value)) {
                return status;
            }
        }
        throw new IllegalStateException("Unknown tenant asset status: " + value);
    }
}
