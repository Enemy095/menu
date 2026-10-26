package com.salamu.menu.notification.internal.application;

import java.util.Objects;

import com.salamu.menu.notification.api.NotificationChannel;
import com.salamu.menu.notification.api.NotificationDestinationId;

/** Server-resolved delivery target. Its secret locator is never rendered. */
public final class DeliveryDestination {

    private final NotificationDestinationId id;
    private final NotificationChannel channel;
    private final OpaqueSecretReference secretReference;

    public DeliveryDestination(
            NotificationDestinationId id,
            NotificationChannel channel,
            OpaqueSecretReference secretReference) {
        this.id = Objects.requireNonNull(id, "id");
        this.channel = Objects.requireNonNull(channel, "channel");
        this.secretReference = Objects.requireNonNull(secretReference, "secretReference");
    }

    public NotificationDestinationId id() {
        return id;
    }

    public NotificationChannel channel() {
        return channel;
    }

    public OpaqueSecretReference secretReference() {
        return secretReference;
    }

    @Override
    public String toString() {
        return "DeliveryDestination[channel=" + channel + ", secretReference=redacted]";
    }
}
