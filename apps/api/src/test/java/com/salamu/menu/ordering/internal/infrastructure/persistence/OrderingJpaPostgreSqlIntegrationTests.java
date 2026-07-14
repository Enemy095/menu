package com.salamu.menu.ordering.internal.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.postgresql.PostgreSQLContainer;

@ActiveProfiles("test")
@SpringBootTest
@Transactional
class OrderingJpaPostgreSqlIntegrationTests {

    private static final UUID RESTAURANT = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID MENU_VERSION = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID CATEGORY = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID MENU_ITEM = UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final UUID ORDER = UUID.fromString("50000000-0000-0000-0000-000000000001");
    private static final UUID ORDER_ITEM = UUID.fromString("60000000-0000-0000-0000-000000000001");
    private static final UUID HISTORY = UUID.fromString("70000000-0000-0000-0000-000000000001");
    private static final UUID OUTBOX = UUID.fromString("80000000-0000-0000-0000-000000000001");

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.5-alpine3.21")
            .withDatabaseName("ordering_persistence")
            .withUsername("ordering_persistence")
            .withPassword("ordering_persistence");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private OrderCounterJpaRepository counterRepository;

    @Autowired
    private OrderJpaRepository orderRepository;

    @Autowired
    private OrderItemJpaRepository itemRepository;

    @Autowired
    private OrderStatusHistoryJpaRepository historyRepository;

    @Autowired
    private OutboxEventJpaRepository outboxRepository;

    @Test
    void finalV7MappingsReadGlobalIdentitySnapshotsHistoryCounterAndOutbox() {
        insertFixture();

        OrderEntity order = orderRepository.findByIdempotencyKey("idem-1").orElseThrow();
        assertThat(order.id).isEqualTo(ORDER);
        assertThat(orderRepository.findByOrderNumber(42).orElseThrow()).isSameAs(order);
        assertThat(order.idempotencyPayloadHash).isEqualTo("a".repeat(64));
        assertThat(order.serverTotalMinor).isEqualTo(12_500L);

        assertThat(itemRepository.findAllByOrderIdOrderBySortOrder(ORDER))
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.id).isEqualTo(ORDER_ITEM);
                    assertThat(item.itemName).isEqualTo("Snapshot item");
                    assertThat(item.unitPriceMinor).isEqualTo(6_250L);
                    assertThat(item.quantity).isEqualTo(2);
                    assertThat(item.lineTotalMinor).isEqualTo(12_500L);
                    assertThat(item.modifierSnapshot).isEmpty();
                });
        assertThat(historyRepository.findAllByOrderIdOrderByOccurredAtAscIdAsc(ORDER))
                .singleElement()
                .satisfies(history -> assertThat(history.actorType).isEqualTo(OrderStatusActorType.CLIENT_ADMIN));
        assertThat(outboxRepository.findAllByProcessingStateOrderByAvailableAtAscCreatedAtAscIdAsc("pending"))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.id).isEqualTo(OUTBOX);
                    assertThat(event.aggregateId).isEqualTo(ORDER);
                    assertThat(event.payload).containsEntry("orderNumber", 42);
                });
        assertThat(counterRepository.lockSingleton()).get().satisfies(counter -> {
            assertThat(counter.singletonId).isEqualTo((short) 1);
            assertThat(counter.nextValue).isEqualTo(43);
        });
    }

    private void insertFixture() {
        jdbc.update("""
                INSERT INTO restaurants (id, external_key, slug, display_name, status, is_default)
                VALUES (?, 'main', 'main', 'Main', 'active', true)
                """, RESTAURANT);
        jdbc.update("""
                INSERT INTO menu_versions (
                    id, restaurant_id, version, status, published_at, currency
                ) VALUES (?, ?, 1, 'published', now(), 'RUB')
                """, MENU_VERSION, RESTAURANT);
        jdbc.update("""
                INSERT INTO menu_categories (
                    id, menu_version_id, public_id, name, sort_order, available
                ) VALUES (?, ?, 'main', 'Main', 0, true)
                """, CATEGORY, MENU_VERSION);
        jdbc.update("""
                INSERT INTO menu_items (
                    id, menu_version_id, category_id, public_id, name, price_minor,
                    currency, available, sort_order
                ) VALUES (?, ?, ?, 'item', 'Item', 6250, 'RUB', true, 0)
                """, MENU_ITEM, MENU_VERSION, CATEGORY);
        jdbc.update("INSERT INTO order_counter (singleton_id, next_value) VALUES (1, 43)");
        jdbc.update("""
                INSERT INTO orders (
                    id, restaurant_id, menu_version_id, order_number, idempotency_key,
                    idempotency_payload_hash, status, order_type, channel,
                    customer_name, customer_phone, client_total_minor, server_total_minor,
                    currency, client_created_at, client_menu_version, needs_confirmation
                ) VALUES (
                    ?, ?, ?, 42, 'idem-1', ?, 'new', 'preorder', 'web',
                    'Customer', '+79990000000', 12500, 12500,
                    'RUB', now(), 1, false
                )
                """, ORDER, RESTAURANT, MENU_VERSION, "a".repeat(64));
        jdbc.update("""
                INSERT INTO order_items (
                    id, order_id, menu_version_id, menu_item_id, menu_item_public_id,
                    item_name, unit_price_minor, modifier_snapshot, quantity,
                    line_total_minor, sort_order
                ) VALUES (
                    ?, ?, ?, ?, 'item', 'Snapshot item', 6250,
                    CAST('[]' AS jsonb), 2, 12500, 0
                )
                """, ORDER_ITEM, ORDER, MENU_VERSION, MENU_ITEM);
        jdbc.update("""
                INSERT INTO order_status_history (
                    id, order_id, from_status, to_status, actor_type, actor_id,
                    reason, metadata, occurred_at
                ) VALUES (
                    ?, ?, NULL, 'new', 'client_admin', 'admin-1',
                    'Created', CAST('{}' AS jsonb), now()
                )
                """, HISTORY, ORDER);
        jdbc.update("""
                INSERT INTO outbox_events (
                    id, aggregate_type, aggregate_id, event_type, schema_version,
                    payload, processing_state, attempt_count, available_at, created_at
                ) VALUES (
                    ?, 'order', ?, 'order.created', 1,
                    CAST('{"orderNumber":42}' AS jsonb), 'pending', 0, now(), now()
                )
                """, OUTBOX, ORDER);
    }
}
