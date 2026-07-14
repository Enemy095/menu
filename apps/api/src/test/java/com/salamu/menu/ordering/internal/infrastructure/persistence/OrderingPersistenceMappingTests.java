package com.salamu.menu.ordering.internal.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Lock;

import jakarta.persistence.Column;
import jakarta.persistence.LockModeType;
import jakarta.persistence.Table;

class OrderingPersistenceMappingTests {

    @Test
    void mapsEveryP2AggregateToItsFinalGlobalTableWithoutOwnerColumns() throws Exception {
        Map<Class<?>, String> mappings = Map.of(
                OrderCounterEntity.class, "order_counter",
                OrderEntity.class, "orders",
                OrderItemEntity.class, "order_items",
                OrderStatusHistoryEntity.class, "order_status_history",
                OutboxEventEntity.class, "outbox_events");

        mappings.forEach((type, table) -> assertThat(type.getAnnotation(Table.class).name()).isEqualTo(table));
        assertThat(fields(OrderCounterEntity.class)).containsExactlyInAnyOrder(
                "singletonId", "nextValue", "updatedAt");
        assertThat(fields(OrderEntity.class)).containsExactlyInAnyOrder(
                "id", "restaurantId", "menuVersionId", "orderNumber", "idempotencyKey",
                "idempotencyPayloadHash", "status", "orderType", "channel", "customerName",
                "customerPhone", "customerAddress", "comment", "guestCount", "serverTotalMinor",
                "currency", "serverReceivedAt", "clientMenuVersion", "needsConfirmation",
                "createdAt", "updatedAt");
        assertThat(fields(OrderItemEntity.class)).containsExactlyInAnyOrder(
                "id", "orderId", "menuVersionId", "menuItemId", "menuItemPublicId", "itemName",
                "unitPriceMinor", "modifierSnapshot", "quantity", "lineTotalMinor", "sortOrder");
        assertThat(fields(OrderStatusHistoryEntity.class)).containsExactlyInAnyOrder(
                "id", "orderId", "fromStatus", "toStatus", "actorType", "actorId", "reason",
                "metadata", "occurredAt");
        assertThat(fields(OutboxEventEntity.class)).containsExactlyInAnyOrder(
                "id", "aggregateType", "aggregateId", "eventType", "schemaVersion", "payload",
                "processingState", "attemptCount", "availableAt", "leasedUntil", "leaseOwner",
                "lastErrorCode", "lastErrorAt", "processedAt", "createdAt");

        assertThat(OrderJpaRepository.class.getDeclaredMethod("findByIdempotencyKey", String.class))
                .isNotNull();
        assertThat(OrderJpaRepository.class.getDeclaredMethod("findByOrderNumber", long.class))
                .isNotNull();
        assertThat(OrderItemJpaRepository.class.getDeclaredMethod(
                "findAllByOrderIdOrderBySortOrder", java.util.UUID.class)).isNotNull();
    }

    @Test
    void idempotencyAndOrderNumberMappingsAreGloballyUnique() throws Exception {
        Column idempotency = OrderEntity.class.getDeclaredField("idempotencyKey").getAnnotation(Column.class);
        Column orderNumber = OrderEntity.class.getDeclaredField("orderNumber").getAnnotation(Column.class);

        assertThat(idempotency.unique()).isTrue();
        assertThat(orderNumber.unique()).isTrue();
    }

    @Test
    void singletonCounterLookupUsesAnExplicitPessimisticWriteLockWithoutAllocating() throws Exception {
        var method = OrderCounterJpaRepository.class.getDeclaredMethod("lockSingleton");

        assertThat(method.getAnnotation(Lock.class).value()).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
        assertThat(Arrays.stream(OrderCounterJpaRepository.class.getDeclaredMethods())
                .map(java.lang.reflect.Method::getName))
                .containsExactly("lockSingleton");
    }

    @Test
    void actorConverterEmitsOnlyTheFinalSchemaValuesAndRejectsUnknownHistory() {
        var converter = new OrderStatusActorTypeConverter();

        assertThat(Arrays.stream(OrderStatusActorType.values())
                .map(converter::convertToDatabaseColumn))
                .containsExactlyInAnyOrder("system", "customer", "client_admin", "worker", "import");
        assertThatThrownBy(() -> converter.convertToEntityAttribute("obsolete_admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unknown order status actor type");
    }

    private static java.util.List<String> fields(Class<?> type) {
        return Arrays.stream(type.getDeclaredFields()).map(java.lang.reflect.Field::getName).toList();
    }
}
