package com.salamu.menu.ordering.internal.infrastructure.persistence;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.salamu.menu.ordering.internal.application.OrderStore;
import com.salamu.menu.ordering.internal.application.PreparedOrder;
import com.salamu.menu.ordering.internal.application.StoredOrder;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcOrderStore implements OrderStore {

    private static final String FIND_BY_IDEMPOTENCY = """
            SELECT id, order_number, idempotency_payload_hash, status, server_total_minor,
                   currency, server_received_at, needs_confirmation
              FROM orders
             WHERE idempotency_key = :idempotencyKey
            """;

    private static final String INSERT_ORDER = """
            INSERT INTO orders (
                id, restaurant_id, menu_version_id, order_number,
                idempotency_key, idempotency_payload_hash, status, order_type, channel,
                customer_name, customer_phone, customer_address, comment, guest_count,
                server_total_minor, currency, server_received_at, client_menu_version,
                needs_confirmation, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, 'new', ?, 'web', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String INSERT_ITEM = """
            INSERT INTO order_items (
                id, order_id, menu_version_id, menu_item_id, menu_item_public_id,
                item_name, unit_price_minor, modifier_snapshot, quantity,
                line_total_minor, sort_order
            ) VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?)
            """;

    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    JdbcOrderStore(JdbcClient jdbc, JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<StoredOrder> findByIdempotencyKey(String idempotencyKey) {
        return jdbc.sql(FIND_BY_IDEMPOTENCY)
                .param("idempotencyKey", idempotencyKey)
                .query((row, number) -> new StoredOrder(
                        row.getObject("id", UUID.class),
                        row.getLong("order_number"),
                        row.getString("idempotency_payload_hash"),
                        row.getString("status"),
                        row.getLong("server_total_minor"),
                        row.getString("currency"),
                        row.getObject("server_received_at", OffsetDateTime.class).toInstant(),
                        row.getBoolean("needs_confirmation")))
                .optional();
    }

    @Override
    public StoredOrder insert(
            String idempotencyKey,
            String payloadHash,
            PreparedOrder order,
            Instant receivedAt) {
        UUID orderId = UUID.randomUUID();
        long orderNumber = nextOrderNumber(receivedAt);
        OffsetDateTime timestamp = receivedAt.atOffset(ZoneOffset.UTC);
        insertOrder(orderId, orderNumber, idempotencyKey, payloadHash, order, timestamp);
        insertItems(orderId, order);
        insertHistory(orderId, timestamp);
        insertOutbox(orderId, orderNumber, order, timestamp);
        return new StoredOrder(
                orderId,
                orderNumber,
                payloadHash,
                "new",
                order.totalMinor(),
                order.currency(),
                receivedAt,
                order.needsConfirmation());
    }

    private long nextOrderNumber(Instant now) {
        jdbcTemplate.update("""
                INSERT INTO order_counter (singleton_id, next_value, updated_at)
                VALUES (1, 1, ?)
                ON CONFLICT (singleton_id) DO NOTHING
                """, now.atOffset(ZoneOffset.UTC));
        Long value = jdbcTemplate.queryForObject("""
                UPDATE order_counter
                   SET next_value = next_value + 1, updated_at = ?
                 WHERE singleton_id = 1 AND next_value < 9223372036854775807
                RETURNING next_value - 1
                """, Long.class, now.atOffset(ZoneOffset.UTC));
        if (value == null || value < 1) {
            throw new IllegalStateException("order number allocation failed");
        }
        return value;
    }

    private void insertOrder(
            UUID orderId,
            long orderNumber,
            String idempotencyKey,
            String payloadHash,
            PreparedOrder order,
            OffsetDateTime timestamp) {
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(INSERT_ORDER);
            int parameter = 1;
            statement.setObject(parameter++, orderId);
            statement.setObject(parameter++, order.restaurantId());
            statement.setObject(parameter++, order.menuVersionId());
            statement.setLong(parameter++, orderNumber);
            statement.setString(parameter++, idempotencyKey);
            statement.setString(parameter++, payloadHash);
            statement.setString(parameter++, order.command().orderType().contractValue());
            statement.setString(parameter++, order.command().customer().name());
            statement.setString(parameter++, order.command().customer().phone());
            nullableString(statement, parameter++, order.command().fulfillment().address());
            nullableString(statement, parameter++, order.command().comment());
            nullableInteger(statement, parameter++, order.command().fulfillment().guestCount());
            statement.setLong(parameter++, order.totalMinor());
            statement.setString(parameter++, order.currency());
            statement.setObject(parameter++, timestamp);
            statement.setLong(parameter++, order.menuVersion());
            statement.setBoolean(parameter++, order.needsConfirmation());
            statement.setObject(parameter++, timestamp);
            statement.setObject(parameter, timestamp);
            return statement;
        });
    }

    private void insertItems(UUID orderId, PreparedOrder order) {
        jdbcTemplate.batchUpdate(INSERT_ITEM, order.lines(), order.lines().size(), (statement, line) -> {
            int parameter = 1;
            statement.setObject(parameter++, UUID.randomUUID());
            statement.setObject(parameter++, orderId);
            statement.setObject(parameter++, order.menuVersionId());
            statement.setObject(parameter++, line.menuItemId());
            statement.setString(parameter++, line.menuItemPublicId());
            statement.setString(parameter++, line.itemName());
            statement.setLong(parameter++, line.unitPriceMinor());
            statement.setString(parameter++, json(line.modifiers()));
            statement.setInt(parameter++, line.quantity());
            statement.setLong(parameter++, line.lineTotalMinor());
            statement.setInt(parameter, line.sortOrder());
        });
    }

    private void insertHistory(UUID orderId, OffsetDateTime timestamp) {
        jdbcTemplate.update("""
                INSERT INTO order_status_history (
                    id, order_id, from_status, to_status, actor_type, actor_id,
                    reason, metadata, occurred_at
                ) VALUES (?, ?, NULL, 'new', 'system', NULL, NULL, '{}'::jsonb, ?)
                """, UUID.randomUUID(), orderId, timestamp);
    }

    private void insertOutbox(
            UUID orderId,
            long orderNumber,
            PreparedOrder order,
            OffsetDateTime timestamp) {
        jdbcTemplate.update("""
                INSERT INTO outbox_events (
                    id, aggregate_type, aggregate_id, event_type, schema_version, payload,
                    processing_state, attempt_count, available_at, created_at
                ) VALUES (?, 'order', ?, 'order.created', 1, CAST(? AS jsonb), 'pending', 0, ?, ?)
                """, UUID.randomUUID(), orderId, json(outboxPayload(orderId, orderNumber, order, timestamp)),
                timestamp, timestamp);
    }

    private static Map<String, Object> outboxPayload(
            UUID orderId,
            long orderNumber,
            PreparedOrder order,
            OffsetDateTime timestamp) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("orderId", orderId.toString());
        payload.put("orderNumber", Long.toString(orderNumber));
        payload.put("status", "new");
        payload.put("orderType", order.command().orderType().contractValue());
        payload.put("customerName", order.command().customer().name());
        payload.put("customerPhone", order.command().customer().phone());
        putIfNotNull(payload, "customerAddress", order.command().fulfillment().address());
        putIfNotNull(payload, "comment", order.command().comment());
        putIfNotNull(payload, "guestCount", order.command().fulfillment().guestCount());
        payload.put("serverTotalMinor", order.totalMinor());
        payload.put("currency", order.currency());
        payload.put("serverReceivedAt", timestamp.toInstant().toString());
        payload.put("needsConfirmation", order.needsConfirmation());
        var items = new ArrayList<Map<String, Object>>(order.lines().size());
        for (var line : order.lines()) {
            var item = new LinkedHashMap<String, Object>();
            item.put("menuItemId", line.menuItemPublicId());
            item.put("name", line.itemName());
            item.put("unitPriceMinor", line.unitPriceMinor());
            item.put("quantity", line.quantity());
            item.put("lineTotalMinor", line.lineTotalMinor());
            item.put("modifiers", line.modifiers());
            items.add(item);
        }
        payload.put("items", items);
        return payload;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("order persistence JSON encoding failed", exception);
        }
    }

    private static void nullableString(PreparedStatement statement, int parameter, String value) throws SQLException {
        if (value == null) {
            statement.setNull(parameter, Types.VARCHAR);
        } else {
            statement.setString(parameter, value);
        }
    }

    private static void nullableInteger(PreparedStatement statement, int parameter, Integer value) throws SQLException {
        if (value == null) {
            statement.setNull(parameter, Types.INTEGER);
        } else {
            statement.setInt(parameter, value);
        }
    }

    private static void putIfNotNull(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }
}
