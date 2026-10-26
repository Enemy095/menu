package com.salamu.menu.notification.internal.infrastructure.ordering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.salamu.menu.notification.internal.application.NotificationOutboxGateway.ClaimRequest;
import com.salamu.menu.notification.internal.application.NotificationOutboxGateway.LeaseCompletion;
import com.salamu.menu.notification.internal.application.NotificationOutboxGateway.LeaseFailure;
import com.salamu.menu.notification.internal.application.NotificationOutboxGateway.LeaseRetry;
import com.salamu.menu.notification.internal.application.NotificationOutboxGateway.TransitionResult;
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

class OrderingOutboxGatewayTests {

    private static final Instant NOW = Instant.parse("2026-07-14T12:00:00Z");
    private static final UUID EVENT_ID = new UUID(0, 1);
    private static final UUID ORDER_ID = new UUID(0, 2);

    private final OrderOutboxPort ordering = mock(OrderOutboxPort.class);
    private final OrderingOutboxGateway gateway = new OrderingOutboxGateway(ordering);

    @Test
    void mapsClaimMetadataAndKeepsPayloadRedacted() {
        byte[] payload = "{\"phone\":\"private-phone\"}".getBytes(StandardCharsets.UTF_8);
        when(ordering.claim(any())).thenReturn(List.of(new ClaimedOutboxEvent(
                new OutboxEventId(EVENT_ID),
                ORDER_ID,
                "order.created",
                1,
                new OutboxPayload(payload),
                2,
                NOW.minusSeconds(5))));

        var events = gateway.claim(new ClaimRequest("worker-a", 3, 10, NOW, NOW.plusSeconds(30)));

        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.eventId()).isEqualTo(EVENT_ID);
            assertThat(event.payload().providerValue()).isEqualTo(new String(payload, StandardCharsets.UTF_8));
            assertThat(event.leasedUntil()).isEqualTo(NOW.plusSeconds(30));
            assertThat(event.toString()).doesNotContain(
                    "private-phone", EVENT_ID.toString(), ORDER_ID.toString());
            assertThat(event.payload().toString()).isEqualTo("OpaqueEventPayload[redacted]");
        });
        var claim = ArgumentCaptor.forClass(OutboxClaimRequest.class);
        verify(ordering).claim(claim.capture());
        assertThat(claim.getValue().batchSize()).isEqualTo(3);
        assertThat(claim.getValue().leaseUntil()).isEqualTo(NOW.plusSeconds(30));
    }

    @Test
    void mapsLeaseGuardedTransitionsAndBacklogWithoutReachingOrderingInternals() {
        when(ordering.complete(any())).thenReturn(OutboxTransitionResult.APPLIED);
        when(ordering.retry(any())).thenReturn(OutboxTransitionResult.LEASE_LOST);
        when(ordering.fail(any())).thenReturn(OutboxTransitionResult.APPLIED);
        when(ordering.backlog(NOW)).thenReturn(new OutboxBacklogSnapshot(2, 1, 4, NOW.minusSeconds(60)));

        assertThat(gateway.complete(new LeaseCompletion(EVENT_ID, "worker-a", 2, NOW)))
                .isEqualTo(TransitionResult.APPLIED);
        assertThat(gateway.retry(new LeaseRetry(
                EVENT_ID, "worker-a", 2, NOW.plusSeconds(5), "REMOTE_TIMEOUT", NOW)))
                .isEqualTo(TransitionResult.LEASE_LOST);
        assertThat(gateway.fail(new LeaseFailure(EVENT_ID, "worker-a", 2, "PERMANENT_FAILURE", NOW)))
                .isEqualTo(TransitionResult.APPLIED);
        assertThat(gateway.backlog(NOW)).satisfies(backlog -> {
            assertThat(backlog.pending()).isEqualTo(2);
            assertThat(backlog.processing()).isEqualTo(1);
            assertThat(backlog.failed()).isEqualTo(4);
            assertThat(backlog.oldestAvailableAt()).isEqualTo(NOW.minusSeconds(60));
        });

        verify(ordering).complete(any(OutboxCompletion.class));
        verify(ordering).retry(any(OutboxRetry.class));
        verify(ordering).fail(any(OutboxFailure.class));
    }
}
