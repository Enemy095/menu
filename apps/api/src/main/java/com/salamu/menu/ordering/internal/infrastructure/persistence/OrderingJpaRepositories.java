package com.salamu.menu.ordering.internal.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.repository.Repository;

interface TenantOrderCounterJpaRepository extends Repository<TenantOrderCounterEntity, UUID> {

    Optional<TenantOrderCounterEntity> findByTenantId(UUID tenantId);
}

interface OrderJpaRepository extends Repository<OrderEntity, UUID> {

    Optional<OrderEntity> findByTenantIdAndId(UUID tenantId, UUID orderId);

    Optional<OrderEntity> findByTenantIdAndIdempotencyKey(UUID tenantId, String idempotencyKey);

    Optional<OrderEntity> findByTenantIdAndOrderNumber(UUID tenantId, long orderNumber);
}

interface OrderItemJpaRepository extends Repository<OrderItemEntity, UUID> {

    List<OrderItemEntity> findAllByTenantIdAndOrderIdOrderBySortOrder(UUID tenantId, UUID orderId);
}

interface OrderStatusHistoryJpaRepository extends Repository<OrderStatusHistoryEntity, UUID> {

    List<OrderStatusHistoryEntity> findAllByTenantIdAndOrderIdOrderByOccurredAt(
            UUID tenantId,
            UUID orderId);
}
