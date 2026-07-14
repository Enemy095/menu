package com.salamu.menu.ordering.internal.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

interface OrderCounterJpaRepository extends Repository<OrderCounterEntity, Short> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select counter from OrderCounterPersistenceEntity counter where counter.singletonId = 1")
    Optional<OrderCounterEntity> lockSingleton();
}

interface OrderJpaRepository extends Repository<OrderEntity, UUID> {

    Optional<OrderEntity> findById(UUID orderId);

    Optional<OrderEntity> findByIdempotencyKey(String idempotencyKey);

    Optional<OrderEntity> findByOrderNumber(long orderNumber);
}

interface OrderItemJpaRepository extends Repository<OrderItemEntity, UUID> {

    List<OrderItemEntity> findAllByOrderIdOrderBySortOrder(UUID orderId);
}

interface OrderStatusHistoryJpaRepository extends Repository<OrderStatusHistoryEntity, UUID> {

    List<OrderStatusHistoryEntity> findAllByOrderIdOrderByOccurredAtAscIdAsc(UUID orderId);
}

interface OutboxEventJpaRepository extends Repository<OutboxEventEntity, UUID> {

    List<OutboxEventEntity> findAllByProcessingStateOrderByAvailableAtAscCreatedAtAscIdAsc(
            String processingState);

    List<OutboxEventEntity> findAllByAggregateTypeAndAggregateIdOrderByCreatedAtAsc(
            String aggregateType,
            UUID aggregateId);
}
