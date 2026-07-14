package com.salamu.menu.notification.internal.infrastructure.persistence;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import com.salamu.menu.notification.internal.application.OpaqueSecretReference;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Converter;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Converter
class OpaqueSecretReferenceConverter implements AttributeConverter<OpaqueSecretReference, String> {

    @Override
    public String convertToDatabaseColumn(OpaqueSecretReference attribute) {
        return attribute == null ? null : attribute.persistenceValue();
    }

    @Override
    public OpaqueSecretReference convertToEntityAttribute(String value) {
        return new OpaqueSecretReference(value);
    }
}

@Entity(name = "NotificationDestinationPersistenceEntity")
@Table(name = "notification_destinations")
class NotificationDestinationEntity {

    @Id
    UUID id;

    @Column(nullable = false, length = 32)
    String channel;

    @Column(nullable = false, length = 32)
    String status;

    @Column(name = "display_name", nullable = false, length = 160)
    String displayName;

    @Convert(converter = OpaqueSecretReferenceConverter.class)
    @Column(name = "secret_reference", nullable = false, length = 512)
    OpaqueSecretReference secretReference;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    Map<String, Object> metadata;

    @Column(name = "created_at", nullable = false)
    OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    OffsetDateTime updatedAt;
}

@Entity(name = "NotificationDeliveryPersistenceEntity")
@Table(name = "notification_deliveries")
class NotificationDeliveryEntity {

    @Id
    UUID id;

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
