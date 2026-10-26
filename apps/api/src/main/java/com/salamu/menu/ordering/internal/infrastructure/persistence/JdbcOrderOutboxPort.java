package com.salamu.menu.ordering.internal.infrastructure.persistence;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import com.salamu.menu.ordering.api.ClaimedOutboxEvent;
import com.salamu.menu.ordering.api.OrderOutboxPort;
import com.salamu.menu.ordering.api.OutboxBacklogSnapshot;
import com.salamu.menu.ordering.api.OutboxClaimRequest;
import com.salamu.menu.ordering.api.OutboxCompletion;
import com.salamu.menu.ordering.api.OutboxEventId;
import com.salamu.menu.ordering.api.OutboxFailure;
import com.salamu.menu.ordering.api.OutboxPayload;
import com.salamu.menu.ordering.api.OutboxRetry;
import com.salamu.menu.ordering.api.OutboxTransitionResult;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcOrderOutboxPort implements OrderOutboxPort {

    private static final String CLAIM = """
            WITH exhausted_candidates AS (
                SELECT event.id
                  FROM outbox_events event
                 WHERE event.attempt_count >= :maxAttempts
                   AND ((event.processing_state = 'pending' AND event.available_at <= :now)
                     OR (event.processing_state = 'processing' AND event.leased_until <= :now))
                 ORDER BY event.available_at, event.created_at, event.id
                 FOR UPDATE SKIP LOCKED
                 LIMIT :batchSize
            ), exhausted AS (
                UPDATE outbox_events event
                   SET processing_state = 'failed',
                       leased_until = NULL,
                       lease_owner = NULL,
                       last_error_code = 'ATTEMPTS_EXHAUSTED',
                       last_error_at = :now
                  FROM exhausted_candidates
                 WHERE event.id = exhausted_candidates.id
                RETURNING event.id
            ), candidates AS (
                SELECT event.id
                  FROM outbox_events event
                 WHERE event.attempt_count < :maxAttempts
                   AND ((event.processing_state = 'pending' AND event.available_at <= :now)
                     OR (event.processing_state = 'processing' AND event.leased_until <= :now))
                 ORDER BY event.available_at, event.created_at, event.id
                 FOR UPDATE SKIP LOCKED
                 LIMIT :batchSize
            )
            UPDATE outbox_events event
               SET processing_state = 'processing',
                   attempt_count = event.attempt_count + 1,
                   leased_until = :leaseUntil,
                   lease_owner = :workerId,
                   last_error_code = NULL,
                   last_error_at = NULL,
                   processed_at = NULL
              FROM candidates
             WHERE event.id = candidates.id
            RETURNING event.id, event.aggregate_id, event.event_type, event.schema_version,
                      event.payload::text AS payload_json, event.attempt_count, event.created_at
            """;

    private final JdbcClient jdbc;

    public JdbcOrderOutboxPort(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public List<ClaimedOutboxEvent> claim(OutboxClaimRequest request) {
        return jdbc.sql(CLAIM)
                .param("now", utc(request.now()))
                .param("leaseUntil", utc(request.leaseUntil()))
                .param("workerId", request.workerId())
                .param("batchSize", request.batchSize())
                .param("maxAttempts", request.maxAttempts())
                .query((row, number) -> new ClaimedOutboxEvent(
                        new OutboxEventId(row.getObject("id", java.util.UUID.class)),
                        row.getObject("aggregate_id", java.util.UUID.class),
                        row.getString("event_type"),
                        row.getInt("schema_version"),
                        new OutboxPayload(row.getString("payload_json").getBytes(StandardCharsets.UTF_8)),
                        row.getInt("attempt_count"),
                        row.getObject("created_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    @Override
    @Transactional
    public OutboxTransitionResult complete(OutboxCompletion completion) {
        int updated = jdbc.sql("""
                        UPDATE outbox_events
                           SET processing_state = 'published', leased_until = NULL, lease_owner = NULL,
                               processed_at = :completedAt, last_error_code = NULL, last_error_at = NULL
                         WHERE id = :id AND processing_state = 'processing' AND lease_owner = :workerId
                           AND attempt_count = :attemptNumber
                           AND leased_until > :completedAt
                        """)
                .param("id", completion.eventId().value())
                .param("workerId", completion.workerId())
                .param("attemptNumber", completion.attemptNumber())
                .param("completedAt", utc(completion.completedAt()))
                .update();
        return result(updated);
    }

    @Override
    @Transactional
    public OutboxTransitionResult retry(OutboxRetry retry) {
        int updated = jdbc.sql("""
                        UPDATE outbox_events
                           SET processing_state = 'pending', leased_until = NULL, lease_owner = NULL,
                               available_at = :availableAt, last_error_code = :errorCode,
                               last_error_at = :failedAt, processed_at = NULL
                         WHERE id = :id AND processing_state = 'processing' AND lease_owner = :workerId
                           AND attempt_count = :attemptNumber
                           AND leased_until > :failedAt
                        """)
                .param("id", retry.eventId().value())
                .param("workerId", retry.workerId())
                .param("attemptNumber", retry.attemptNumber())
                .param("failedAt", utc(retry.failedAt()))
                .param("availableAt", utc(retry.availableAt()))
                .param("errorCode", retry.errorCode())
                .update();
        return result(updated);
    }

    @Override
    @Transactional
    public OutboxTransitionResult fail(OutboxFailure failure) {
        int updated = jdbc.sql("""
                        UPDATE outbox_events
                           SET processing_state = 'failed', leased_until = NULL, lease_owner = NULL,
                               last_error_code = :errorCode, last_error_at = :failedAt, processed_at = NULL
                         WHERE id = :id AND processing_state = 'processing' AND lease_owner = :workerId
                           AND attempt_count = :attemptNumber
                           AND leased_until > :failedAt
                        """)
                .param("id", failure.eventId().value())
                .param("workerId", failure.workerId())
                .param("attemptNumber", failure.attemptNumber())
                .param("failedAt", utc(failure.failedAt()))
                .param("errorCode", failure.errorCode())
                .update();
        return result(updated);
    }

    @Override
    @Transactional(readOnly = true)
    public OutboxBacklogSnapshot backlog(Instant now) {
        return jdbc.sql("""
                        SELECT count(*) FILTER (WHERE processing_state = 'pending') AS pending_count,
                               count(*) FILTER (WHERE processing_state = 'processing') AS processing_count,
                               count(*) FILTER (WHERE processing_state = 'failed') AS failed_count,
                               min(available_at) FILTER (WHERE processing_state = 'pending') AS oldest_pending_at
                          FROM outbox_events
                        """)
                .query((row, number) -> {
                    var oldest = row.getObject("oldest_pending_at", OffsetDateTime.class);
                    return new OutboxBacklogSnapshot(
                            row.getLong("pending_count"),
                            row.getLong("processing_count"),
                            row.getLong("failed_count"),
                            oldest == null ? null : oldest.toInstant());
                })
                .single();
    }

    private static OffsetDateTime utc(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }

    private static OutboxTransitionResult result(int updated) {
        return updated == 1 ? OutboxTransitionResult.APPLIED : OutboxTransitionResult.LEASE_LOST;
    }
}
