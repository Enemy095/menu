package com.salamu.menu.notification.internal.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import com.salamu.menu.ordering.api.OrderOutboxPort;
import com.salamu.menu.ordering.api.OutboxClaimRequest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

@ActiveProfiles("test")
@SpringBootTest
class FinalAttemptCrashRecoveryPostgreSqlTests {

    private static final Instant NOW = Instant.parse("2026-07-14T12:00:00Z");
    private static final UUID RESTAURANT = new UUID(0, 901);
    private static final UUID MENU_VERSION = new UUID(0, 902);
    private static final UUID ORDER = new UUID(0, 903);
    private static final UUID DESTINATION = new UUID(0, 904);
    private static final UUID OUTBOX = new UUID(0, 905);
    private static final UUID DELIVERY = new UUID(0, 906);
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.5-alpine3.21")
            .withDatabaseName("final_attempt_crash_recovery")
            .withUsername("final_attempt_crash_recovery")
            .withPassword("final_attempt_crash_recovery");

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
    private OrderOutboxPort outbox;

    @Autowired
    private NotificationDeliveryAuditService audit;

    @BeforeEach
    void insertCrashedFinalAttempt() {
        jdbc.execute("TRUNCATE TABLE restaurants CASCADE");
        jdbc.update("""
                INSERT INTO restaurants (id, external_key, slug, display_name, status, is_default)
                VALUES (?, 'main', 'main', 'Main', 'active', true)
                """, RESTAURANT);
        jdbc.update("""
                INSERT INTO menu_versions (id, restaurant_id, version, status, published_at, currency)
                VALUES (?, ?, 1, 'published', ?, 'RUB')
                """, MENU_VERSION, RESTAURANT, NOW.minusSeconds(180).atOffset(ZoneOffset.UTC));
        jdbc.update("""
                INSERT INTO orders (
                    id, restaurant_id, menu_version_id, order_number, idempotency_key,
                    idempotency_payload_hash, status, order_type, channel,
                    customer_name, customer_phone, server_total_minor,
                    currency, client_menu_version, needs_confirmation
                ) VALUES (
                    ?, ?, ?, 1, '11111111-1111-4111-8111-111111111111', ?, 'new',
                    'pickup', 'web', 'Customer', '+79990000000', 10000, 'RUB', 1, false
                )
                """, ORDER, RESTAURANT, MENU_VERSION, "a".repeat(64));
        jdbc.update("""
                INSERT INTO notification_destinations (
                    id, channel, status, display_name, secret_reference, metadata
                ) VALUES (?, 'telegram', 'active', 'Operations',
                    'secret://application/telegram/operations', CAST('{}' AS jsonb))
                """, DESTINATION);
        jdbc.update("""
                INSERT INTO outbox_events (
                    id, aggregate_type, aggregate_id, event_type, schema_version, payload,
                    processing_state, attempt_count, available_at, lease_owner, leased_until, created_at
                ) VALUES (
                    ?, 'order', ?, 'order.created', 1, CAST('{"order":"redacted"}' AS jsonb),
                    'processing', 1, ?, 'shared-worker', ?, ?
                )
                """,
                OUTBOX,
                ORDER,
                NOW.minusSeconds(120).atOffset(ZoneOffset.UTC),
                NOW.minusSeconds(60).atOffset(ZoneOffset.UTC),
                NOW.minusSeconds(180).atOffset(ZoneOffset.UTC));
        jdbc.update("""
                INSERT INTO notification_deliveries (
                    id, outbox_event_id, destination_id, attempt_number, status, started_at
                ) VALUES (?, ?, ?, 1, 'started', ?)
                """, DELIVERY, OUTBOX, DESTINATION, NOW.minusSeconds(90).atOffset(ZoneOffset.UTC));
    }

    @Test
    void finalAttemptExhaustionAndNotificationSweepLeaveNoStartedAudit() {
        assertThat(outbox.claim(new OutboxClaimRequest(
                "shared-worker", NOW, NOW.plusSeconds(30), 1, 1))).isEmpty();
        assertThat(jdbc.queryForObject(
                "SELECT processing_state FROM outbox_events WHERE id = ?",
                String.class,
                OUTBOX)).isEqualTo("failed");

        assertThat(audit.reconcileStaleStarted(NOW, Duration.ofSeconds(30), 10)).isEqualTo(1);

        assertThat(jdbc.queryForMap("""
                SELECT status, error_code, error_message, completed_at
                  FROM notification_deliveries
                 WHERE id = ?
                """, DELIVERY))
                .containsEntry("status", "failed")
                .containsEntry("error_code", "LEASE_EXPIRED_UNKNOWN_OUTCOME")
                .containsEntry("error_message", null)
                .containsKey("completed_at");
    }
}
