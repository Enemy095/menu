package com.salamu.menu.notification.internal.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.repository.Repository;

interface NotificationDestinationJpaRepository extends Repository<NotificationDestinationEntity, UUID> {

    Optional<NotificationDestinationEntity> findByTenantIdAndId(UUID tenantId, UUID destinationId);

    List<NotificationDestinationEntity> findAllByTenantIdAndChannelAndStatus(
            UUID tenantId,
            String channel,
            String status);
}

interface OutboxEventJpaRepository extends Repository<OutboxEventEntity, UUID> {

    Optional<OutboxEventEntity> findByTenantIdAndId(UUID tenantId, UUID eventId);

    List<OutboxEventEntity> findAllByTenantIdAndProcessingStateOrderByAvailableAt(
            UUID tenantId,
            String processingState);
}

interface NotificationDeliveryJpaRepository extends Repository<NotificationDeliveryEntity, UUID> {

    List<NotificationDeliveryEntity> findAllByTenantIdAndOutboxEventIdOrderByAttemptNumber(
            UUID tenantId,
            UUID outboxEventId);
}
