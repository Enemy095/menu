package com.salamu.menu.notification.internal.infrastructure.ordering;

import java.nio.charset.StandardCharsets;
import java.util.List;

import com.salamu.menu.notification.internal.application.ClaimedNotificationEvent;
import com.salamu.menu.notification.internal.application.NotificationOutboxGateway;
import com.salamu.menu.notification.internal.application.OpaqueEventPayload;
import com.salamu.menu.ordering.api.OrderOutboxPort;
import com.salamu.menu.ordering.api.OutboxClaimRequest;
import com.salamu.menu.ordering.api.OutboxCompletion;
import com.salamu.menu.ordering.api.OutboxEventId;
import com.salamu.menu.ordering.api.OutboxFailure;
import com.salamu.menu.ordering.api.OutboxRetry;
import com.salamu.menu.ordering.api.OutboxTransitionResult;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "app.worker", name = "enabled", havingValue = "true")
class OrderingOutboxGateway implements NotificationOutboxGateway {

    private final OrderOutboxPort orderingOutbox;

    OrderingOutboxGateway(OrderOutboxPort orderingOutbox) {
        this.orderingOutbox = orderingOutbox;
    }

    @Override
    public List<ClaimedNotificationEvent> claim(ClaimRequest request) {
        var orderingRequest = new OutboxClaimRequest(
                request.workerId(),
                request.now(),
                request.leasedUntil(),
                request.limit(),
                request.maxAttempts());
        return orderingOutbox.claim(orderingRequest).stream()
                .map(event -> new ClaimedNotificationEvent(
                        event.eventId().value(),
                        event.eventType(),
                        event.schemaVersion(),
                        new OpaqueEventPayload(new String(event.payload().jsonUtf8(), StandardCharsets.UTF_8)),
                        event.attemptNumber(),
                        event.createdAt(),
                        request.leasedUntil()))
                .toList();
    }

    @Override
    public TransitionResult complete(LeaseCompletion completion) {
        return map(orderingOutbox.complete(new OutboxCompletion(
                new OutboxEventId(completion.eventId()),
                completion.workerId(),
                completion.attemptNumber(),
                completion.completedAt())));
    }

    @Override
    public TransitionResult retry(LeaseRetry retry) {
        return map(orderingOutbox.retry(new OutboxRetry(
                new OutboxEventId(retry.eventId()),
                retry.workerId(),
                retry.attemptNumber(),
                retry.failedAt(),
                retry.availableAt(),
                retry.errorCode())));
    }

    @Override
    public TransitionResult fail(LeaseFailure failure) {
        return map(orderingOutbox.fail(new OutboxFailure(
                new OutboxEventId(failure.eventId()),
                failure.workerId(),
                failure.attemptNumber(),
                failure.failedAt(),
                failure.errorCode())));
    }

    @Override
    public BacklogSnapshot backlog(java.time.Instant now) {
        var snapshot = orderingOutbox.backlog(now);
        return new BacklogSnapshot(
                snapshot.pendingCount(),
                snapshot.processingCount(),
                snapshot.failedCount(),
                snapshot.oldestPendingAt());
    }

    private static TransitionResult map(OutboxTransitionResult result) {
        return switch (result) {
            case APPLIED -> TransitionResult.APPLIED;
            case LEASE_LOST -> TransitionResult.LEASE_LOST;
        };
    }
}
