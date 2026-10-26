package com.salamu.menu.notification.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;

import org.junit.jupiter.api.Test;

import com.salamu.menu.notification.internal.application.NotificationOutboxGateway.BacklogSnapshot;
import com.salamu.menu.notification.internal.infrastructure.config.WorkerProperties;
import com.salamu.menu.notification.internal.infrastructure.observability.NotificationWorkerMetrics;

import io.micrometer.observation.ObservationRegistry;

class NotificationOutboxCoordinatorTests {

    private static final Instant NOW = Instant.parse("2026-07-14T12:00:00Z");

    @Test
    void neverClaimsBeyondAvailableConcurrencyAndStopsClaimingOnShutdown() {
        var outbox = mock(NotificationOutboxGateway.class);
        var processor = mock(OutboxEventProcessor.class);
        var audit = mock(NotificationDeliveryAuditService.class);
        var metrics = mock(NotificationWorkerMetrics.class);
        var executor = new QueuedExecutor();
        when(outbox.claim(any())).thenReturn(List.of(event(1), event(2)));
        when(outbox.backlog(any())).thenReturn(new BacklogSnapshot(0, 2, 0, null));
        var coordinator = new NotificationOutboxCoordinator(
                outbox,
                processor,
                audit,
                properties(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                executor,
                metrics,
                ObservationRegistry.create());

        coordinator.poll();
        coordinator.poll();

        assertThat(coordinator.inFlightCount()).isEqualTo(2);
        assertThat(executor.tasks).hasSize(2);
        verify(outbox, times(1)).claim(any());
        verify(audit, times(2)).reconcileStaleStarted(NOW, Duration.ofSeconds(30), 10);
        executor.runAll();
        assertThat(coordinator.inFlightCount()).isZero();
        verify(processor, times(2)).process(any());

        coordinator.stopClaiming();
        coordinator.poll();
        verify(outbox, times(1)).claim(any());
    }

    @Test
    void processorFailureStillReleasesCapacityForTheNextPoll() {
        var outbox = mock(NotificationOutboxGateway.class);
        var processor = mock(OutboxEventProcessor.class);
        var audit = mock(NotificationDeliveryAuditService.class);
        var metrics = mock(NotificationWorkerMetrics.class);
        when(outbox.claim(any())).thenReturn(List.of(event(1)));
        when(outbox.backlog(any())).thenReturn(new BacklogSnapshot(0, 1, 0, null));
        org.mockito.Mockito.doThrow(new IllegalStateException("sensitive payload"))
                .when(processor).process(any());
        var coordinator = new NotificationOutboxCoordinator(
                outbox,
                processor,
                audit,
                properties(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                Runnable::run,
                metrics,
                ObservationRegistry.create());

        coordinator.poll();
        coordinator.poll();

        assertThat(coordinator.inFlightCount()).isZero();
        verify(outbox, times(2)).claim(any());
    }

    private static ClaimedNotificationEvent event(long suffix) {
        return new ClaimedNotificationEvent(
                new UUID(0, suffix),
                "order.created",
                1,
                new OpaqueEventPayload("{\"phone\":\"private\"}"),
                1,
                NOW.minusSeconds(10),
                NOW.plusSeconds(30));
    }

    private static WorkerProperties properties() {
        return new WorkerProperties(
                true,
                "test-worker",
                Duration.ofSeconds(1),
                10,
                2,
                2,
                Duration.ofSeconds(30),
                Duration.ofSeconds(5),
                4,
                Duration.ofSeconds(1),
                Duration.ofMinutes(1),
                0,
                Duration.ofSeconds(5));
    }

    private static final class QueuedExecutor implements Executor {

        private final List<Runnable> tasks = new ArrayList<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        void runAll() {
            List.copyOf(tasks).forEach(Runnable::run);
            tasks.clear();
        }
    }
}
