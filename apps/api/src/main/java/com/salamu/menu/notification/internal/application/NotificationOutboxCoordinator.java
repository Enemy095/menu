package com.salamu.menu.notification.internal.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.salamu.menu.notification.internal.application.NotificationOutboxGateway.ClaimRequest;
import com.salamu.menu.notification.internal.infrastructure.config.WorkerProperties;
import com.salamu.menu.notification.internal.infrastructure.observability.NotificationWorkerMetrics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

@Component
@ConditionalOnProperty(prefix = "app.worker", name = "enabled", havingValue = "true")
public class NotificationOutboxCoordinator {

    private static final Logger LOGGER = LoggerFactory.getLogger(NotificationOutboxCoordinator.class);

    private final NotificationOutboxGateway outbox;
    private final OutboxEventProcessor processor;
    private final NotificationDeliveryAuditService deliveryAudit;
    private final WorkerProperties properties;
    private final Clock clock;
    private final Executor executor;
    private final NotificationWorkerMetrics metrics;
    private final ObservationRegistry observationRegistry;
    private final AtomicBoolean polling = new AtomicBoolean();
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final AtomicInteger inFlight = new AtomicInteger();

    public NotificationOutboxCoordinator(
            NotificationOutboxGateway outbox,
            OutboxEventProcessor processor,
            NotificationDeliveryAuditService deliveryAudit,
            WorkerProperties properties,
            @Qualifier("notificationWorkerClock") Clock clock,
            @Qualifier("notificationWorkerExecutor") Executor executor,
            NotificationWorkerMetrics metrics,
            ObservationRegistry observationRegistry) {
        this.outbox = outbox;
        this.processor = processor;
        this.deliveryAudit = deliveryAudit;
        this.properties = properties;
        this.clock = clock;
        this.executor = executor;
        this.metrics = metrics;
        this.observationRegistry = observationRegistry;
    }

    @Scheduled(fixedDelayString = "${app.worker.poll-interval:1s}")
    public void poll() {
        if (stopping.get() || !polling.compareAndSet(false, true)) {
            return;
        }
        try {
            Instant now = clock.instant();
            int reconciled = deliveryAudit.reconcileStaleStarted(
                    now,
                    properties.leaseDuration(),
                    properties.batchSize());
            metrics.auditsReconciled(reconciled);
            int capacity = Math.max(0, properties.concurrency() - inFlight.get());
            if (capacity == 0) {
                return;
            }
            int claimSize = Math.min(capacity, properties.batchSize());
            List<ClaimedNotificationEvent> events = outbox.claim(new ClaimRequest(
                    properties.workerId(),
                    claimSize,
                    properties.maxAttempts(),
                    now,
                    now.plus(properties.leaseDuration())));
            metrics.claimed(events.size());
            for (ClaimedNotificationEvent event : events) {
                submit(event);
            }
            metrics.updateBacklog(outbox.backlog(clock.instant()), clock.instant());
        } catch (RuntimeException exception) {
            metrics.pollFailure();
            LOGGER.atWarn()
                    .addKeyValue("code", "OUTBOX_POLL_FAILED")
                    .log("Notification outbox poll failed");
        } finally {
            polling.set(false);
        }
    }

    @EventListener(ContextClosedEvent.class)
    public void stopClaiming() {
        stopping.set(true);
    }

    int inFlightCount() {
        return inFlight.get();
    }

    private void submit(ClaimedNotificationEvent event) {
        inFlight.incrementAndGet();
        try {
            executor.execute(() -> {
                try {
                    Observation.createNotStarted("menu.notification.outbox.process", observationRegistry)
                            .lowCardinalityKeyValue(
                                    "event.type",
                                    "order.created".equals(event.eventType()) ? "order.created" : "unsupported")
                            .lowCardinalityKeyValue(
                                    "schema.version",
                                    event.schemaVersion() == 1 ? "1" : "unsupported")
                            .observe(() -> processor.process(event));
                } catch (RuntimeException exception) {
                    LOGGER.atWarn()
                            .addKeyValue("code", "OUTBOX_PROCESSING_INTERRUPTED")
                            .addKeyValue("eventType", event.eventType())
                            .addKeyValue("schemaVersion", event.schemaVersion())
                            .addKeyValue("attempt", event.attemptNumber())
                            .log("Notification event processing ended without transition");
                } finally {
                    inFlight.decrementAndGet();
                }
            });
        } catch (RuntimeException exception) {
            inFlight.decrementAndGet();
            LOGGER.atWarn()
                    .addKeyValue("code", "OUTBOX_EXECUTOR_REJECTED")
                    .log("Notification event remains leased for crash recovery");
        }
    }
}
