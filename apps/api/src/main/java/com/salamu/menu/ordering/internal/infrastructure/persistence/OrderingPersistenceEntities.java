package com.salamu.menu.ordering.internal.infrastructure.persistence;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity(name = "TenantOrderCounterPersistenceEntity")
@Table(name = "tenant_order_counters")
class TenantOrderCounterEntity {

    @Id
    @Column(name = "tenant_id")
    UUID tenantId;

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

    @Column(name = "tenant_id", nullable = false)
    UUID tenantId;

    @Column(name = "restaurant_id", nullable = false)
    UUID restaurantId;

    @Column(name = "menu_version_id", nullable = false)
    UUID menuVersionId;

    @Column(name = "order_number", nullable = false)
    long orderNumber;

    @Column(name = "idempotency_key", nullable = false, length = 128)
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

    @Column(name = "tenant_id", nullable = false)
    UUID tenantId;

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

@Entity(name = "OrderStatusHistoryPersistenceEntity")
@Table(name = "order_status_history")
class OrderStatusHistoryEntity {

    @Id
    UUID id;

    @Column(name = "tenant_id", nullable = false)
    UUID tenantId;

    @Column(name = "order_id", nullable = false)
    UUID orderId;

    @Column(name = "from_status", length = 32)
    String fromStatus;

    @Column(name = "to_status", nullable = false, length = 32)
    String toStatus;

    @Column(name = "actor_type", nullable = false, length = 32)
    String actorType;

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
