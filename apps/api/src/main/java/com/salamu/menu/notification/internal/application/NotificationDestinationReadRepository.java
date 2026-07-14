package com.salamu.menu.notification.internal.application;

import java.util.List;

import com.salamu.menu.notification.api.NotificationChannel;
import com.salamu.menu.notification.api.NotificationDestinationId;
import com.salamu.menu.notification.api.NotificationDestinationStatus;

public interface NotificationDestinationReadRepository {

    List<StoredDestination> findAllOrdered();

    record StoredDestination(
            NotificationDestinationId id,
            NotificationChannel channel,
            NotificationDestinationStatus status,
            String displayName,
            OpaqueSecretReference secretReference) {
    }
}
