package com.salamu.menu.notification.internal.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.UUID;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.salamu.menu.notification.api.NotificationDestinationId;
import com.salamu.menu.notification.internal.application.NotificationDeliveryAuditService;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NotificationDeliveryAuditPostgreSqlTests {

    private static final UUID RESTAURANT = new UUID(0, 101);
    private static final UUID MENU_VERSION = new UUID(0, 102);
    private static final UUID ORDER = new UUID(0, 103);
    private static final UUID OUTBOX = new UUID(0, 104);
    private static final NotificationDestinationId DESTINATION =
            new NotificationDestinationId(new UUID(0, 105));
    private static final Instant NOW = Instant.parse("2026-07-14T12:00:00Z");

    private PostgreSQLContainer postgres;
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private NotificationDeliveryAuditService audit;

    @BeforeAll
    void startPostgreSqlAndApplicationContext() {
        postgres = new PostgreSQLContainer("postgres:17.5-alpine3.21")
                .withDatabaseName("notification_delivery_audit")
                .withUsername("notification_delivery_audit")
                .withPassword("notification_delivery_audit");
        postgres.start();
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .load()
                .migrate();

        DataSource dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        context = new AnnotationConfigApplicationContext();
        context.register(TransactionConfiguration.class);
        context.registerBean(DataSource.class, () -> dataSource);
        context.registerBean(JdbcClient.class, () -> JdbcClient.create(dataSource));
        context.registerBean(DataSourceTransactionManager.class, () -> new DataSourceTransactionManager(dataSource));
        context.registerBean(JdbcNotificationDeliveryAuditRepository.class);
        context.registerBean(NotificationDeliveryAuditService.class);
        context.refresh();

        jdbc = new JdbcTemplate(dataSource);
        audit = context.getBean(NotificationDeliveryAuditService.class);
    }

    @AfterAll
    void stopResources() {
        if (context != null) {
            context.close();
        }
        if (postgres != null) {
            postgres.stop();
        }
    }

    @BeforeEach
    void resetAndInsertFixture() {
        jdbc.update("DELETE FROM notification_deliveries");
        jdbc.update("DELETE FROM outbox_events");
        jdbc.update("DELETE FROM notification_destinations");
        jdbc.update("DELETE FROM order_status_history");
        jdbc.update("DELETE FROM order_items");
        jdbc.update("DELETE FROM orders");
        jdbc.update("DELETE FROM menu_modifier_options");
        jdbc.update("DELETE FROM menu_modifier_groups");
        jdbc.update("DELETE FROM menu_items");
        jdbc.update("DELETE FROM menu_categories");
        jdbc.update("DELETE FROM menu_versions");
        jdbc.update("DELETE FROM order_counter");
        jdbc.update("DELETE FROM restaurants");

        jdbc.update("""
                INSERT INTO restaurants (id, external_key, slug, display_name, status, is_default)
                VALUES (?, 'main', 'main', 'Main', 'active', true)
                """, RESTAURANT);
        jdbc.update("""
                INSERT INTO menu_versions (id, restaurant_id, version, status, published_at, currency)
                VALUES (?, ?, 1, 'published', now(), 'RUB')
                """, MENU_VERSION, RESTAURANT);
        jdbc.update("""
                INSERT INTO orders (
                    id, restaurant_id, menu_version_id, order_number, idempotency_key,
                    idempotency_payload_hash, status, order_type, channel,
                    customer_name, customer_phone, server_total_minor,
                    currency, client_menu_version, needs_confirmation
                ) VALUES (
                    ?, ?, ?, 1, '11111111-1111-4111-8111-111111111111', ?, 'new',
                    'preorder', 'web', 'Customer', '+79990000000', 10000, 'RUB', 1, false
                )
                """, ORDER, RESTAURANT, MENU_VERSION, "a".repeat(64));
        jdbc.update("""
                INSERT INTO notification_destinations (
                    id, channel, status, display_name, secret_reference, metadata
                ) VALUES (?, 'telegram', 'active', 'Operations',
                    'secret://application/telegram/operations', CAST('{}' AS jsonb))
                """, DESTINATION.value());
        jdbc.update("""
                INSERT INTO outbox_events (
                    id, aggregate_type, aggregate_id, event_type, schema_version,
                    payload, processing_state, attempt_count, available_at,
                    lease_owner, leased_until, created_at
                ) VALUES (?, 'order', ?, 'order.created', 1,
                    CAST('{"order":"redacted"}' AS jsonb), 'processing', 1, ?,
                    'test-worker', ?, ?)
                """, OUTBOX, ORDER,
                NOW.minusSeconds(1).atOffset(ZoneOffset.UTC),
                NOW.plusSeconds(30).atOffset(ZoneOffset.UTC),
                NOW.minusSeconds(10).atOffset(ZoneOffset.UTC));
    }

    @Test
    void persistsStartedAndSucceededAuditAndReturnsSucceededDestination() {
        var attempt = audit.start(OUTBOX, DESTINATION, 1, NOW);
        audit.succeed(attempt, "provider-message-private", NOW.plusSeconds(1));

        assertThat(audit.succeededDestinations(OUTBOX)).containsExactly(DESTINATION);
        assertThat(jdbc.queryForMap("""
                SELECT status, provider_message_id, error_code, error_message, completed_at
                  FROM notification_deliveries
                 WHERE id = ?
                """, attempt.deliveryId()))
                .containsEntry("status", "succeeded")
                .containsEntry("provider_message_id", "provider-message-private")
                .containsEntry("error_code", null)
                .containsEntry("error_message", null)
                .containsKey("completed_at");
    }

    @Test
    void nextAttemptClosesAmbiguousStartedAuditWithoutPersistingSensitiveErrorText() {
        var first = audit.start(OUTBOX, DESTINATION, 1, NOW);
        var second = audit.start(OUTBOX, DESTINATION, 2, NOW.plusSeconds(31));
        audit.fail(second, "REMOTE_TIMEOUT", NOW.plusSeconds(32));

        assertThat(jdbc.queryForMap("""
                SELECT status, error_code, error_message, completed_at
                  FROM notification_deliveries
                 WHERE id = ?
                """, first.deliveryId()))
                .containsEntry("status", "failed")
                .containsEntry("error_code", "LEASE_EXPIRED_UNKNOWN_OUTCOME")
                .containsEntry("error_message", null)
                .containsKey("completed_at");
        assertThat(jdbc.queryForMap("""
                SELECT status, error_code, error_message
                  FROM notification_deliveries
                 WHERE id = ?
                """, second.deliveryId()))
                .containsEntry("status", "failed")
                .containsEntry("error_code", "REMOTE_TIMEOUT")
                .containsEntry("error_message", null);
    }

    @Test
    void allowsSuccessfulProviderResultWithoutOptionalMessageId() {
        var attempt = audit.start(OUTBOX, DESTINATION, 1, NOW);

        audit.succeed(attempt, null, NOW.plusSeconds(1));

        assertThat(jdbc.queryForMap("""
                SELECT status, provider_message_id, completed_at
                  FROM notification_deliveries
                 WHERE id = ?
                """, attempt.deliveryId()))
                .containsEntry("status", "succeeded")
                .containsEntry("provider_message_id", null)
                .containsKey("completed_at");
    }

    @Test
    void startTransactionRollsBackAbandonedUpdateWhenNewAttemptConflicts() {
        var first = audit.start(OUTBOX, DESTINATION, 1, NOW);
        jdbc.update("""
                INSERT INTO notification_deliveries (
                    id, outbox_event_id, destination_id, attempt_number, status,
                    error_code, started_at, completed_at
                ) VALUES (?, ?, ?, 2, 'failed', 'PREEXISTING_ATTEMPT', ?, ?)
                """, new UUID(0, 999), OUTBOX, DESTINATION.value(),
                NOW.atOffset(ZoneOffset.UTC), NOW.atOffset(ZoneOffset.UTC));

        assertThatThrownBy(() -> audit.start(OUTBOX, DESTINATION, 2, NOW.plusSeconds(31)))
                .isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM notification_deliveries WHERE id = ?",
                String.class,
                first.deliveryId())).isEqualTo("started");
    }

    @Test
    void staleStartedReconciliationIsBoundedAndLeavesFreshWorkUntouched() {
        insertStarted(new UUID(0, 801), 1, NOW.minusSeconds(90));
        insertStarted(new UUID(0, 802), 2, NOW.minusSeconds(80));
        insertStarted(new UUID(0, 803), 3, NOW.minusSeconds(70));
        insertStarted(new UUID(0, 804), 4, NOW.minusSeconds(10));

        assertThat(audit.reconcileStaleStarted(NOW, Duration.ofSeconds(30), 2)).isEqualTo(2);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM notification_deliveries WHERE status = 'failed'",
                Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM notification_deliveries WHERE status = 'started'",
                Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM notification_deliveries
                 WHERE status = 'failed'
                   AND error_code = 'LEASE_EXPIRED_UNKNOWN_OUTCOME'
                   AND error_message IS NULL
                   AND completed_at IS NOT NULL
                """, Long.class)).isEqualTo(2);
    }

    private void insertStarted(UUID deliveryId, int attemptNumber, Instant startedAt) {
        jdbc.update("""
                INSERT INTO notification_deliveries (
                    id, outbox_event_id, destination_id, attempt_number, status, started_at
                ) VALUES (?, ?, ?, ?, 'started', ?)
                """, deliveryId, OUTBOX, DESTINATION.value(), attemptNumber, startedAt.atOffset(ZoneOffset.UTC));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class TransactionConfiguration {
    }
}
