package com.salamu.menu.notification.api;

import java.util.Objects;
import java.util.UUID;

public record NotificationDestinationId(UUID value) {

    public NotificationDestinationId {
        Objects.requireNonNull(value, "value");
    }

    @Override
    public String toString() {
        return "NotificationDestinationId[redacted]";
    }
}
