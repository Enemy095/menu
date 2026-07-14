package com.salamu.menu.notification.api;

/** Channels supported by the persisted destination schema. */
public enum NotificationChannel {
    WHATSAPP("whatsapp"),
    TELEGRAM("telegram"),
    EMAIL("email"),
    POS("pos");

    private final String databaseValue;

    NotificationChannel(String databaseValue) {
        this.databaseValue = databaseValue;
    }

    public String databaseValue() {
        return databaseValue;
    }

    public static NotificationChannel fromDatabaseValue(String value) {
        for (var channel : values()) {
            if (channel.databaseValue.equals(value)) {
                return channel;
            }
        }
        throw new IllegalArgumentException("Unknown notification channel");
    }
}
