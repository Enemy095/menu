package com.salamu.menu.notification.api;

/** Operational state stored for a notification destination. */
public enum NotificationDestinationStatus {
    ACTIVE("active"),
    DISABLED("disabled");

    private final String databaseValue;

    NotificationDestinationStatus(String databaseValue) {
        this.databaseValue = databaseValue;
    }

    public String databaseValue() {
        return databaseValue;
    }

    public static NotificationDestinationStatus fromDatabaseValue(String value) {
        for (var status : values()) {
            if (status.databaseValue.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown notification destination status");
    }
}
