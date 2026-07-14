package com.salamu.menu.notification.internal.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.repository.Repository;

interface NotificationDestinationJpaRepository extends Repository<NotificationDestinationEntity, UUID> {

    Optional<NotificationDestinationEntity> findById(UUID destinationId);

    List<NotificationDestinationEntity> findAllByChannelAndStatusOrderByDisplayName(
            String channel,
            String status);
}

interface NotificationDeliveryJpaRepository extends Repository<NotificationDeliveryEntity, UUID> {

    List<NotificationDeliveryEntity> findAllByOutboxEventIdOrderByAttemptNumber(UUID outboxEventId);
}
