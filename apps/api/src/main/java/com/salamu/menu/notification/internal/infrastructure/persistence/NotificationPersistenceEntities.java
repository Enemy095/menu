package com.salamu.menu.notification.internal.infrastructure.persistence;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity(name = "NotificationDestinationPersistenceEntity")
@Table(name = "notification_destinations")
class NotificationDestinationEntity {

    @Id
    UUID id;

    @Column(name = "tenant_id", nullable = false)
    UUID tenantId;

    @Column(nullable = false, length = 32)
    String channel;

    @Column(nullable = false, length = 32)
    String status;

    @Column(name = "display_name", nullable = false, length = 160)
    String displayName;

    @Column(name = "secret_reference", nullable = false, length = 512)
    String secretReference;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    Map<String, Object> metadata;

    @Column(name = "created_at", nullable = false)
    OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    OffsetDateTime updatedAt;
}

@Entity(name = "OutboxEventPersistenceEntity")
@Table(name = "outbox_events")
class OutboxEventEntity {

    @Id
    UUID id;

    @Column(name = "tenant_id", nullable = false)
    UUID tenantId;

    @Column(name = "aggregate_type", nullable = false, length = 80)
    String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    UUID aggregateId;

    @Column(name = "event_type", nullable = false, length = 120)
    String eventType;

    @Column(name = "schema_version", nullable = false)
    int schemaVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    Map<String, Object> payload;

    @Column(name = "processing_state", nullable = false, length = 32)
    String processingState;

    @Column(name = "attempt_count", nullable = false)
    int attemptCount;

    @Column(name = "available_at", nullable = false)
    OffsetDateTime availableAt;

    @Column(name = "leased_until")
    OffsetDateTime leasedUntil;

    @Column(name = "lease_owner", length = 160)
    String leaseOwner;

    @Column(name = "last_error_code", length = 120)
    String lastErrorCode;

    @Column(name = "last_error_at")
    OffsetDateTime lastErrorAt;

    @Column(name = "processed_at")
    OffsetDateTime processedAt;

    @Column(name = "created_at", nullable = false)
    OffsetDateTime createdAt;
}

@Entity(name = "NotificationDeliveryPersistenceEntity")
@Table(name = "notification_deliveries")
class NotificationDeliveryEntity {

    @Id
    UUID id;

    @Column(name = "tenant_id", nullable = false)
    UUID tenantId;

    @Column(name = "outbox_event_id", nullable = false)
    UUID outboxEventId;

    @Column(name = "destination_id", nullable = false)
    UUID destinationId;

    @Column(name = "attempt_number", nullable = false)
    int attemptNumber;

    @Column(nullable = false, length = 32)
    String status;

    @Column(name = "provider_message_id", length = 255)
    String providerMessageId;

    @Column(name = "error_code", length = 120)
    String errorCode;

    @Column(name = "error_message", length = 1000)
    String errorMessage;

    @Column(name = "started_at", nullable = false)
    OffsetDateTime startedAt;

    @Column(name = "completed_at")
    OffsetDateTime completedAt;
}
