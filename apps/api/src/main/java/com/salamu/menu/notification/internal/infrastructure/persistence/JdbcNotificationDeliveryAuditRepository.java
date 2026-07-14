package com.salamu.menu.notification.internal.infrastructure.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.salamu.menu.notification.api.NotificationDestinationId;
import com.salamu.menu.notification.internal.application.NotificationDeliveryAuditRepository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcNotificationDeliveryAuditRepository implements NotificationDeliveryAuditRepository {

    private static final String SELECT_SUCCEEDED = """
            SELECT DISTINCT destination_id
              FROM notification_deliveries
             WHERE outbox_event_id = :eventId
               AND status = 'succeeded'
            """;

    private static final String CLOSE_ABANDONED = """
            UPDATE notification_deliveries
               SET status = 'failed',
                   error_code = :errorCode,
                   error_message = NULL,
                   completed_at = :completedAt
             WHERE outbox_event_id = :eventId
               AND destination_id = :destinationId
               AND attempt_number < :beforeAttempt
               AND status = 'started'
            """;

    private static final String INSERT_STARTED = """
            INSERT INTO notification_deliveries (
                id, outbox_event_id, destination_id, attempt_number, status, started_at
            ) VALUES (
                :deliveryId, :eventId, :destinationId, :attemptNumber, 'started', :startedAt
            )
            """;

    private static final String MARK_SUCCEEDED = """
            UPDATE notification_deliveries
               SET status = 'succeeded',
                   provider_message_id = :providerMessageId,
                   error_code = NULL,
                   error_message = NULL,
                   completed_at = :completedAt
             WHERE id = :deliveryId
               AND status = 'started'
            """;

    private static final String MARK_FAILED = """
            UPDATE notification_deliveries
               SET status = 'failed',
                   provider_message_id = NULL,
                   error_code = :errorCode,
                   error_message = NULL,
                   completed_at = :completedAt
             WHERE id = :deliveryId
               AND status = 'started'
            """;

    private static final String CLOSE_STALE_STARTED = """
            WITH candidates AS (
                SELECT delivery.id
                  FROM notification_deliveries delivery
                 WHERE delivery.status = 'started'
                   AND delivery.started_at <= :startedAtOrBefore
                 ORDER BY delivery.started_at, delivery.id
                 FOR UPDATE SKIP LOCKED
                 LIMIT :limit
            )
            UPDATE notification_deliveries delivery
               SET status = 'failed',
                   provider_message_id = NULL,
                   error_code = :errorCode,
                   error_message = NULL,
                   completed_at = :completedAt
              FROM candidates
             WHERE delivery.id = candidates.id
               AND delivery.status = 'started'
            """;

    private final JdbcClient jdbcClient;

    JdbcNotificationDeliveryAuditRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public Set<NotificationDestinationId> succeededDestinations(UUID outboxEventId) {
        return jdbcClient.sql(SELECT_SUCCEEDED)
                .param("eventId", outboxEventId)
                .query(UUID.class)
                .list()
                .stream()
                .map(NotificationDestinationId::new)
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public int closeAbandonedStarted(
            UUID outboxEventId,
            NotificationDestinationId destinationId,
            int beforeAttempt,
            Instant completedAt,
            String errorCode) {
        return jdbcClient.sql(CLOSE_ABANDONED)
                .param("eventId", outboxEventId)
                .param("destinationId", destinationId.value())
                .param("beforeAttempt", beforeAttempt)
                .param("completedAt", utc(completedAt))
                .param("errorCode", errorCode)
                .update();
    }

    @Override
    public void insertStarted(
            UUID deliveryId,
            UUID outboxEventId,
            NotificationDestinationId destinationId,
            int attemptNumber,
            Instant startedAt) {
        jdbcClient.sql(INSERT_STARTED)
                .param("deliveryId", deliveryId)
                .param("eventId", outboxEventId)
                .param("destinationId", destinationId.value())
                .param("attemptNumber", attemptNumber)
                .param("startedAt", utc(startedAt))
                .update();
    }

    @Override
    public int markSucceeded(UUID deliveryId, String providerMessageId, Instant completedAt) {
        return jdbcClient.sql(MARK_SUCCEEDED)
                .param("deliveryId", deliveryId)
                .param("providerMessageId", providerMessageId)
                .param("completedAt", utc(completedAt))
                .update();
    }

    @Override
    public int markFailed(UUID deliveryId, String errorCode, Instant completedAt) {
        return jdbcClient.sql(MARK_FAILED)
                .param("deliveryId", deliveryId)
                .param("errorCode", errorCode)
                .param("completedAt", utc(completedAt))
                .update();
    }

    @Override
    public int closeStaleStarted(
            Instant startedAtOrBefore,
            int limit,
            Instant completedAt,
            String errorCode) {
        return jdbcClient.sql(CLOSE_STALE_STARTED)
                .param("startedAtOrBefore", utc(startedAtOrBefore))
                .param("limit", limit)
                .param("completedAt", utc(completedAt))
                .param("errorCode", errorCode)
                .update();
    }

    private static OffsetDateTime utc(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }
}
