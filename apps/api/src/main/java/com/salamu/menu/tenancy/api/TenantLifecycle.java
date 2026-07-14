package com.salamu.menu.tenancy.api;

/** Persisted tenant lifecycle visible to privileged provisioning inspection only. */
public enum TenantLifecycle {
    ACTIVE("active"),
    SUSPENDED("suspended"),
    DELETED("deleted");

    private final String databaseValue;

    TenantLifecycle(String databaseValue) {
        this.databaseValue = databaseValue;
    }

    public String databaseValue() {
        return databaseValue;
    }

    public static TenantLifecycle fromDatabaseValue(String value) {
        for (var lifecycle : values()) {
            if (lifecycle.databaseValue.equals(value)) {
                return lifecycle;
            }
        }
        throw new IllegalArgumentException("Unknown tenant lifecycle");
    }
}
