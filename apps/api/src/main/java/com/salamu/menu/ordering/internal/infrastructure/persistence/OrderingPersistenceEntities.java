package com.salamu.menu.ordering.internal.infrastructure.persistence;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Converter;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity(name = "OrderCounterPersistenceEntity")
@Table(name = "order_counter")
class OrderCounterEntity {

    @Id
    @Column(name = "singleton_id")
    short singletonId;

    @Column(name = "next_value", nullable = false)
    long nextValue;

    @Column(name = "updated_at", nullable = false)
    OffsetDateTime updatedAt;
}

@Entity(name = "OrderPersistenceEntity")
@Table(name = "orders")
class OrderEntity {

    @Id
    UUID id;

    @Column(name = "restaurant_id", nullable = false)
    UUID restaurantId;

    @Column(name = "menu_version_id", nullable = false)
    UUID menuVersionId;

    @Column(name = "order_number", nullable = false, unique = true)
    long orderNumber;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 128)
    String idempotencyKey;

    @Column(name = "idempotency_payload_hash", nullable = false, length = 64)
    String idempotencyPayloadHash;

    @Column(nullable = false, length = 32)
    String status;

    @Column(name = "order_type", nullable = false, length = 32)
    String orderType;

    @Column(nullable = false, length = 32)
    String channel;

    @Column(name = "customer_name", nullable = false, length = 80)
    String customerName;

    @Column(name = "customer_phone", nullable = false, length = 32)
    String customerPhone;

    @Column(name = "customer_address", length = 240)
    String customerAddress;

    @Column(length = 500)
    String comment;

    @Column(name = "guest_count")
    Integer guestCount;

    @Column(name = "client_total_minor", nullable = false)
    long clientTotalMinor;

    @Column(name = "server_total_minor", nullable = false)
    long serverTotalMinor;

    @Column(nullable = false, length = 3)
    String currency;

    @Column(name = "client_created_at", nullable = false)
    OffsetDateTime clientCreatedAt;

    @Column(name = "server_received_at", nullable = false)
    OffsetDateTime serverReceivedAt;

    @Column(name = "client_menu_version", nullable = false)
    long clientMenuVersion;

    @Column(name = "needs_confirmation", nullable = false)
    boolean needsConfirmation;

    @Column(name = "created_at", nullable = false)
    OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    OffsetDateTime updatedAt;
}

@Entity(name = "OrderItemPersistenceEntity")
@Table(name = "order_items")
class OrderItemEntity {

    @Id
    UUID id;

    @Column(name = "order_id", nullable = false)
    UUID orderId;

    @Column(name = "menu_version_id", nullable = false)
    UUID menuVersionId;

    @Column(name = "menu_item_id", nullable = false)
    UUID menuItemId;

    @Column(name = "menu_item_public_id", nullable = false, length = 120)
    String menuItemPublicId;

    @Column(name = "item_name", nullable = false, length = 160)
    String itemName;

    @Column(name = "unit_price_minor", nullable = false)
    long unitPriceMinor;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "modifier_snapshot", nullable = false, columnDefinition = "jsonb")
    List<Map<String, Object>> modifierSnapshot;

    @Column(nullable = false)
    int quantity;

    @Column(name = "line_total_minor", nullable = false)
    long lineTotalMinor;

    @Column(name = "sort_order", nullable = false)
    int sortOrder;
}

enum OrderStatusActorType {
    SYSTEM("system"),
    CUSTOMER("customer"),
    CLIENT_ADMIN("client_admin"),
    WORKER("worker"),
    IMPORT("import");

    private final String databaseValue;

    OrderStatusActorType(String databaseValue) {
        this.databaseValue = databaseValue;
    }

    String databaseValue() {
        return databaseValue;
    }

    static OrderStatusActorType fromDatabaseValue(String value) {
        for (var actorType : values()) {
            if (actorType.databaseValue.equals(value)) {
                return actorType;
            }
        }
        throw new IllegalArgumentException("Unknown order status actor type");
    }
}

@Converter
class OrderStatusActorTypeConverter implements AttributeConverter<OrderStatusActorType, String> {

    @Override
    public String convertToDatabaseColumn(OrderStatusActorType attribute) {
        return attribute == null ? null : attribute.databaseValue();
    }

    @Override
    public OrderStatusActorType convertToEntityAttribute(String value) {
        return value == null ? null : OrderStatusActorType.fromDatabaseValue(value);
    }
}

@Entity(name = "OrderStatusHistoryPersistenceEntity")
@Table(name = "order_status_history")
class OrderStatusHistoryEntity {

    @Id
    UUID id;

    @Column(name = "order_id", nullable = false)
    UUID orderId;

    @Column(name = "from_status", length = 32)
    String fromStatus;

    @Column(name = "to_status", nullable = false, length = 32)
    String toStatus;

    @Convert(converter = OrderStatusActorTypeConverter.class)
    @Column(name = "actor_type", nullable = false, length = 32)
    OrderStatusActorType actorType;

    @Column(name = "actor_id", length = 160)
    String actorId;

    @Column(length = 500)
    String reason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    Map<String, Object> metadata;

    @Column(name = "occurred_at", nullable = false)
    OffsetDateTime occurredAt;
}

@Entity(name = "OutboxEventPersistenceEntity")
@Table(name = "outbox_events")
class OutboxEventEntity {

    @Id
    UUID id;

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
