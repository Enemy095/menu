package com.salamu.menu.notification.internal.application;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import com.salamu.menu.notification.api.NotificationDestinationId;

public interface NotificationDeliveryAuditRepository {

    Set<NotificationDestinationId> succeededDestinations(UUID outboxEventId);

    int closeAbandonedStarted(
            UUID outboxEventId,
            NotificationDestinationId destinationId,
            int beforeAttempt,
            Instant completedAt,
            String errorCode);

    void insertStarted(
            UUID deliveryId,
            UUID outboxEventId,
            NotificationDestinationId destinationId,
            int attemptNumber,
            Instant startedAt);

    int markSucceeded(UUID deliveryId, String providerMessageId, Instant completedAt);

    int markFailed(UUID deliveryId, String errorCode, Instant completedAt);

    int closeStaleStarted(Instant startedAtOrBefore, int limit, Instant completedAt, String errorCode);
}
