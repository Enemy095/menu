package com.salamu.menu.ordering.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.salamu.menu.ordering.api.OrderApiException;
import com.salamu.menu.ordering.api.OrderOutboxPort;
import com.salamu.menu.ordering.api.OutboxClaimRequest;
import com.salamu.menu.ordering.api.OutboxCompletion;
import com.salamu.menu.ordering.api.OutboxFailure;
import com.salamu.menu.ordering.api.OutboxRetry;
import com.salamu.menu.ordering.api.OutboxTransitionResult;

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
class PublicOrderCreationPostgreSqlIntegrationTests {

    private static final UUID RESTAURANT = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID MENU = UUID.fromString("20000000-0000-4000-8000-000000000002");
    private static final UUID CATEGORY = UUID.fromString("30000000-0000-4000-8000-000000000003");
    private static final UUID ITEM = UUID.fromString("40000000-0000-4000-8000-000000000004");
    private static final Instant NOW = Instant.parse("2026-07-14T12:00:00Z");
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.5-alpine3.21")
            .withDatabaseName("public_order_creation")
            .withUsername("public_order_creation")
            .withPassword("public_order_creation");

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
    private OrderStore store;

    @Autowired
    private OrderCreationTransaction transaction;

    @Autowired
    private OrderOutboxPort outbox;

    private OrderPreparationService preparation;
    private PublicOrderApplicationService service;

    @BeforeEach
    void resetSchemaAndService() {
        jdbc.execute("TRUNCATE TABLE restaurants, order_counter CASCADE");
        seedMenu();
        preparation = mock(OrderPreparationService.class);
        service = new PublicOrderApplicationService(
                store,
                new OrderPayloadHasher(),
                preparation,
                transaction,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test void closedDayCreatesNeitherOrderNorNotificationAndExistingReplayStillWorks() {
        var command = command(1);
        when(preparation.prepare(command)).thenThrow(OrderApiException.restaurantClosed());
        String key = "10000000-0000-4000-8000-000000000099";
        assertThatThrownBy(() -> service.create(key, command))
                .isInstanceOfSatisfying(OrderApiException.class,
                        failure -> assertThat(failure.code()).isEqualTo(OrderApiException.Code.RESTAURANT_CLOSED));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events", Integer.class)).isZero();
        org.mockito.Mockito.doReturn(prepared(command)).when(preparation).prepare(command);
        var created = service.create(key, command);
        when(preparation.prepare(command)).thenThrow(OrderApiException.restaurantClosed());
        assertThat(service.create(key, command).order().orderId()).isEqualTo(created.order().orderId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders", Integer.class)).isEqualTo(1);
    }

    @Test
    void persistsOrderSnapshotsHistoryAndOutboxInOneTransactionThenReplaysOriginalResult() {
        var command = command(2);
        when(preparation.prepare(command)).thenReturn(prepared(command));
        String key = "10000000-0000-4000-8000-000000000010";

        var created = service.create(key, command);
        var replayed = service.create(key, command);

        assertThat(created.replayed()).isFalse();
        assertThat(replayed.replayed()).isTrue();
        assertThat(replayed.order()).isEqualTo(created.order());
        assertThat(created.order().serverTotalMinor()).isEqualTo(125_000);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_items", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_status_history", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT actor_type FROM order_status_history WHERE order_id = ?",
                String.class,
                created.order().orderId())).isEqualTo("system");
        assertThat(jdbc.queryForObject(
                "SELECT item_name FROM order_items WHERE order_id = ?",
                String.class,
                created.order().orderId())).isEqualTo("Snapshot pepperoni");
        assertThat(jdbc.queryForObject(
                "SELECT payload->>'customerPhone' FROM outbox_events WHERE aggregate_id = ?",
                String.class,
                created.order().orderId())).isEqualTo("+79990000000");
    }

    @Test
    void sameKeyWithDifferentCanonicalPayloadReturnsConflictWithoutCreatingAnotherOrder() {
        var first = command(1);
        when(preparation.prepare(first)).thenReturn(prepared(first));
        String key = "10000000-0000-4000-8000-000000000011";
        service.create(key, first);

        assertThatThrownBy(() -> service.create(key, command(2)))
                .isInstanceOfSatisfying(OrderApiException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo(OrderApiException.Code.IDEMPOTENCY_CONFLICT));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders", Long.class)).isEqualTo(1);
    }

    @Test
    void concurrentIdenticalRequestsProduceOneGlobalOrderAndOneReplay() throws Exception {
        var command = command(1);
        var bothPrepared = new CountDownLatch(2);
        when(preparation.prepare(any())).thenAnswer(invocation -> {
            bothPrepared.countDown();
            if (!bothPrepared.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("concurrent preparation barrier timed out");
            }
            return prepared(command);
        });
        String key = "10000000-0000-4000-8000-000000000012";

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> service.create(key, command));
            var second = executor.submit(() -> service.create(key, command));
            var results = List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));

            assertThat(results).extracting(PublicOrderApplicationService.CreationResult::replayed)
                    .containsExactlyInAnyOrder(false, true);
            assertThat(results.stream().map(result -> result.order().orderId()).distinct()).hasSize(1);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events", Long.class)).isEqualTo(1);
    }

    @Test
    void concurrentDifferentPayloadsForOneKeyCreateOneOrderAndReturnOneConflict() throws Exception {
        var firstCommand = command(1);
        var secondCommand = command(2);
        var bothPrepared = new CountDownLatch(2);
        when(preparation.prepare(any())).thenAnswer(invocation -> {
            bothPrepared.countDown();
            if (!bothPrepared.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("concurrent preparation barrier timed out");
            }
            return prepared(invocation.getArgument(0));
        });
        String key = "10000000-0000-4000-8000-000000000014";

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> service.create(key, firstCommand));
            var second = executor.submit(() -> service.create(key, secondCommand));
            var outcomes = List.of(outcome(first), outcome(second));

            assertThat(outcomes).filteredOn(result -> result.order() != null).hasSize(1);
            assertThat(outcomes).filteredOn(result -> result.errorCode()
                            == OrderApiException.Code.IDEMPOTENCY_CONFLICT)
                    .hasSize(1);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_status_history", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events", Long.class)).isEqualTo(1);
    }

    @Test
    void menuPublishedAfterPreparationIsRejectedInsideWriteTransactionWithoutRows() {
        var command = command(1);
        when(preparation.prepare(command)).thenAnswer(invocation -> {
            jdbc.update("UPDATE menu_versions SET status = 'archived' WHERE id = ?", MENU);
            jdbc.update("""
                    INSERT INTO menu_versions (id, restaurant_id, version, status, published_at, currency)
                    VALUES (?, ?, 8, 'published', ?, 'RUB')
                    """,
                    UUID.fromString("20000000-0000-4000-8000-000000000008"),
                    RESTAURANT,
                    NOW.plusSeconds(1).atOffset(ZoneOffset.UTC));
            return prepared(command);
        });

        assertThatThrownBy(() -> service.create(
                        "10000000-0000-4000-8000-000000000015", command))
                .isInstanceOfSatisfying(OrderApiException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo(OrderApiException.Code.MENU_CHANGED));
        assertAtomicTablesEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_counter", Long.class)).isZero();
    }

    @Test
    void itemForeignKeyFailureRollsBackOrderHistoryOutboxAndCounter() {
        var command = command(1);
        jdbc.update("INSERT INTO order_counter (singleton_id, next_value, updated_at) VALUES (1, 10, ?)",
                NOW.atOffset(ZoneOffset.UTC));
        var valid = prepared(command);
        var invalid = new PreparedOrder(
                valid.restaurantId(), valid.menuVersionId(), valid.menuVersion(), valid.currency(), valid.command(),
                List.of(new PreparedOrder.Line(
                        UUID.fromString("40000000-0000-4000-8000-000000000099"),
                        "pepperoni", "Snapshot pepperoni", 62_500, 1, 62_500, 0, List.of())),
                62_500, false);
        when(preparation.prepare(command)).thenReturn(invalid);

        assertDependencyFailure(() -> service.create(
                "10000000-0000-4000-8000-000000000016", command));

        assertAtomicRollbackToCounter(10);
    }

    @Test
    void forcedOutboxFailureRollsBackOrderItemsHistoryAndCounter() {
        var command = command(1);
        jdbc.update("INSERT INTO order_counter (singleton_id, next_value, updated_at) VALUES (1, 20, ?)",
                NOW.atOffset(ZoneOffset.UTC));
        when(preparation.prepare(command)).thenReturn(prepared(command));
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION reject_test_outbox_insert() RETURNS trigger
                LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'TEST_OUTBOX_REJECTED'; END $$
                """);
        jdbc.execute("""
                CREATE TRIGGER reject_test_outbox_insert
                BEFORE INSERT ON outbox_events
                FOR EACH ROW EXECUTE FUNCTION reject_test_outbox_insert()
                """);
        try {
            assertDependencyFailure(() -> service.create(
                    "10000000-0000-4000-8000-000000000017", command));
            assertAtomicRollbackToCounter(20);
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS reject_test_outbox_insert ON outbox_events");
            jdbc.execute("DROP FUNCTION IF EXISTS reject_test_outbox_insert()");
        }
    }

    @Test
    void claimsAndCompletesCreatedOutboxEventWithLeaseOwnership() {
        var command = command(1);
        when(preparation.prepare(command)).thenReturn(prepared(command));
        service.create("10000000-0000-4000-8000-000000000013", command);

        var claimed = outbox.claim(new OutboxClaimRequest(
                "notification-worker-1", NOW.plusSeconds(1), NOW.plusSeconds(31), 10, 5));

        assertThat(claimed).singleElement().satisfies(event -> {
            assertThat(event.eventType()).isEqualTo("order.created");
            assertThat(event.attemptNumber()).isEqualTo(1);
            assertThat(event.payload().toString()).doesNotContain("+7999", "Snapshot pepperoni");
            var completion = new OutboxCompletion(
                    event.eventId(), "notification-worker-1", event.attemptNumber(), NOW.plusSeconds(2));
            assertThat(outbox.complete(completion)).isEqualTo(OutboxTransitionResult.APPLIED);
            assertThat(outbox.complete(completion)).isEqualTo(OutboxTransitionResult.LEASE_LOST);
        });
        assertThat(outbox.backlog(NOW.plusSeconds(2)).pendingCount()).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT processing_state FROM outbox_events", String.class)).isEqualTo("published");
    }

    @Test
    void concurrentWorkersUseSkipLockedAndAnActiveLeaseCannotBeStolen() throws Exception {
        createOrder("10000000-0000-4000-8000-000000000020");
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return outbox.claim(new OutboxClaimRequest(
                        "worker-a", NOW.plusSeconds(1), NOW.plusSeconds(31), 1, 5));
            });
            var second = executor.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return outbox.claim(new OutboxClaimRequest(
                        "worker-b", NOW.plusSeconds(1), NOW.plusSeconds(31), 1, 5));
            });
            start.countDown();
            var claims = List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
            assertThat(claims.stream().mapToInt(List::size).sum()).isEqualTo(1);
        }

        assertThat(outbox.claim(new OutboxClaimRequest(
                "worker-c", NOW.plusSeconds(2), NOW.plusSeconds(32), 1, 5))).isEmpty();
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM outbox_events", Integer.class)).isEqualTo(1);
    }

    @Test
    void expiredLeaseIsReclaimedAndEveryStaleWorkerTransitionLosesOwnership() {
        createOrder("10000000-0000-4000-8000-000000000021");
        var first = outbox.claim(new OutboxClaimRequest(
                "shared-worker", NOW.plusSeconds(1), NOW.plusSeconds(11), 1, 5)).getFirst();
        var reclaimed = outbox.claim(new OutboxClaimRequest(
                "shared-worker", NOW.plusSeconds(12), NOW.plusSeconds(42), 1, 5)).getFirst();

        assertThat(reclaimed.eventId()).isEqualTo(first.eventId());
        assertThat(reclaimed.attemptNumber()).isEqualTo(2);
        assertThat(outbox.complete(new OutboxCompletion(
                first.eventId(), "shared-worker", first.attemptNumber(), NOW.plusSeconds(13))))
                .isEqualTo(OutboxTransitionResult.LEASE_LOST);
        assertThat(outbox.retry(new OutboxRetry(
                first.eventId(), "shared-worker", first.attemptNumber(),
                NOW.plusSeconds(13), NOW.plusSeconds(20), "TEMPORARY")))
                .isEqualTo(OutboxTransitionResult.LEASE_LOST);
        assertThat(outbox.fail(new OutboxFailure(
                first.eventId(), "shared-worker", first.attemptNumber(), NOW.plusSeconds(13), "PERMANENT")))
                .isEqualTo(OutboxTransitionResult.LEASE_LOST);
        assertThat(outbox.complete(new OutboxCompletion(
                reclaimed.eventId(), "shared-worker", reclaimed.attemptNumber(), NOW.plusSeconds(14))))
                .isEqualTo(OutboxTransitionResult.APPLIED);
    }

    @Test
    void retryIsNotClaimableEarlyAndExhaustedEventsBecomeTerminal() {
        createOrder("10000000-0000-4000-8000-000000000022");
        var first = outbox.claim(new OutboxClaimRequest(
                "worker-a", NOW.plusSeconds(1), NOW.plusSeconds(31), 1, 3)).getFirst();
        assertThat(outbox.retry(new OutboxRetry(
                first.eventId(), "worker-a", first.attemptNumber(),
                NOW.plusSeconds(2), NOW.plusSeconds(60), "TEMPORARY")))
                .isEqualTo(OutboxTransitionResult.APPLIED);
        assertThat(outbox.claim(new OutboxClaimRequest(
                "worker-b", NOW.plusSeconds(30), NOW.plusSeconds(50), 1, 3))).isEmpty();
        var secondAttempt = outbox.claim(new OutboxClaimRequest(
                "worker-b", NOW.plusSeconds(60), NOW.plusSeconds(70), 1, 3)).getFirst();
        assertThat(secondAttempt.attemptNumber()).isEqualTo(2);

        jdbc.update("UPDATE outbox_events SET attempt_count = 3, leased_until = ? WHERE id = ?",
                NOW.plusSeconds(65).atOffset(ZoneOffset.UTC), secondAttempt.eventId().value());
        assertThat(outbox.claim(new OutboxClaimRequest(
                "worker-c", NOW.plusSeconds(66), NOW.plusSeconds(90), 1, 3))).isEmpty();
        assertThat(jdbc.queryForObject(
                "SELECT processing_state FROM outbox_events", String.class)).isEqualTo("failed");
        assertThat(jdbc.queryForObject(
                "SELECT last_error_code FROM outbox_events", String.class)).isEqualTo("ATTEMPTS_EXHAUSTED");
    }

    @Test
    void backlogReportsCountsAndOldestPendingAvailability() {
        createOrder("10000000-0000-4000-8000-000000000023");
        createOrder("10000000-0000-4000-8000-000000000024");
        jdbc.update("""
                UPDATE outbox_events SET available_at = ?
                 WHERE id = (SELECT id FROM outbox_events ORDER BY id DESC LIMIT 1)
                """, NOW.plusSeconds(10).atOffset(ZoneOffset.UTC));

        var backlog = outbox.backlog(NOW);

        assertThat(backlog.pendingCount()).isEqualTo(2);
        assertThat(backlog.processingCount()).isZero();
        assertThat(backlog.failedCount()).isZero();
        assertThat(backlog.oldestPendingAt()).isEqualTo(NOW);
    }

    @Test
    void exhaustedTerminalizationIsBoundedByClaimBatch() {
        createOrder("10000000-0000-4000-8000-000000000025");
        createOrder("10000000-0000-4000-8000-000000000026");
        createOrder("10000000-0000-4000-8000-000000000027");
        jdbc.update("UPDATE outbox_events SET attempt_count = 3, available_at = ?",
                NOW.atOffset(ZoneOffset.UTC));

        assertThat(outbox.claim(new OutboxClaimRequest(
                "worker-a", NOW.plusSeconds(1), NOW.plusSeconds(31), 1, 3))).isEmpty();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE processing_state = 'failed'",
                Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE processing_state = 'pending'",
                Long.class)).isEqualTo(2);

        assertThat(outbox.claim(new OutboxClaimRequest(
                "worker-b", NOW.plusSeconds(2), NOW.plusSeconds(32), 1, 3))).isEmpty();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE processing_state = 'failed'",
                Long.class)).isEqualTo(2);
    }

    private void seedMenu() {
        jdbc.update("""
                INSERT INTO restaurants (id, external_key, slug, display_name, status, is_default)
                VALUES (?, 'main', 'main', 'Main', 'active', true)
                """, RESTAURANT);
        jdbc.update("""
                INSERT INTO menu_versions (id, restaurant_id, version, status, published_at, currency)
                VALUES (?, ?, 7, 'published', ?, 'RUB')
                """, MENU, RESTAURANT, NOW.atOffset(ZoneOffset.UTC));
        jdbc.update("""
                INSERT INTO menu_categories (id, menu_version_id, public_id, name, sort_order, available)
                VALUES (?, ?, 'pizza', 'Pizza', 0, true)
                """, CATEGORY, MENU);
        jdbc.update("""
                INSERT INTO menu_items (
                    id, menu_version_id, category_id, public_id, name, price_minor,
                    currency, available, sort_order
                ) VALUES (?, ?, ?, 'pepperoni', 'Pepperoni', 62500, 'RUB', true, 0)
                """, ITEM, MENU, CATEGORY);
    }

    private void createOrder(String key) {
        var command = command(1);
        when(preparation.prepare(command)).thenReturn(prepared(command));
        service.create(key, command);
    }

    private void assertAtomicRollbackToCounter(long expectedNextValue) {
        assertAtomicTablesEmpty();
        assertThat(jdbc.queryForObject(
                "SELECT next_value FROM order_counter WHERE singleton_id = 1", Long.class))
                .isEqualTo(expectedNextValue);
    }

    private void assertAtomicTablesEmpty() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_items", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_status_history", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events", Long.class)).isZero();
    }

    private static void assertDependencyFailure(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(OrderApiException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo(OrderApiException.Code.DEPENDENCY_UNAVAILABLE));
    }

    private static ConcurrentOutcome outcome(Future<PublicOrderApplicationService.CreationResult> future)
            throws Exception {
        try {
            return new ConcurrentOutcome(future.get(20, TimeUnit.SECONDS).order(), null);
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof OrderApiException orderException) {
                return new ConcurrentOutcome(null, orderException.code());
            }
            throw exception;
        }
    }

    private record ConcurrentOutcome(StoredOrder order, OrderApiException.Code errorCode) {
    }

    private static CreateOrderCommand command(int quantity) {
        return new CreateOrderCommand(
                CreateOrderCommand.OrderType.PICKUP,
                new CreateOrderCommand.Customer("Customer", "+79990000000"),
                new CreateOrderCommand.Fulfillment(null, 2),
                List.of(new CreateOrderCommand.Item("pepperoni", quantity, List.of())),
                "Comment",
                7);
    }

    private static PreparedOrder prepared(CreateOrderCommand command) {
        int quantity = command.items().getFirst().quantity();
        return new PreparedOrder(
                RESTAURANT,
                MENU,
                7,
                "RUB",
                command,
                List.of(new PreparedOrder.Line(
                        ITEM,
                        "pepperoni",
                        "Snapshot pepperoni",
                        62_500,
                        quantity,
                        Math.multiplyExact(62_500L, quantity),
                        0,
                        List.of())),
                Math.multiplyExact(62_500L, quantity),
                false);
    }
}
