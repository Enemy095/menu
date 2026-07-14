package com.salamu.menu.notification.internal.infrastructure.observability;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

import com.salamu.menu.notification.api.NotificationChannel;
import com.salamu.menu.notification.internal.application.NotificationOutboxGateway.BacklogSnapshot;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

@Component
@ConditionalOnProperty(prefix = "app.worker", name = "enabled", havingValue = "true")
public class NotificationWorkerMetrics {

    private final MeterRegistry registry;
    private final Counter claimed;
    private final AtomicLong pending = new AtomicLong();
    private final AtomicLong processing = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong oldestPendingSeconds = new AtomicLong();

    public NotificationWorkerMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.claimed = Counter.builder("menu.notification.outbox.claimed")
                .description("Claimed outbox events")
                .register(registry);
        registerGauge("menu.notification.outbox.pending", "Pending outbox events", pending);
        registerGauge("menu.notification.outbox.processing", "Processing outbox events", processing);
        registerGauge("menu.notification.outbox.failed", "Terminal failed outbox events", failed);
        registerGauge(
                "menu.notification.outbox.oldest.pending.seconds",
                "Age of the oldest pending event",
                oldestPendingSeconds);
    }

    public void claimed(int count) {
        if (count > 0) {
            claimed.increment(count);
        }
    }

    public void providerCall(NotificationChannel channel, String outcome, Duration duration) {
        Counter.builder("menu.notification.provider.calls")
                .tag("channel", channel.databaseValue())
                .tag("outcome", outcome)
                .register(registry)
                .increment();
        Timer.builder("menu.notification.provider.duration")
                .tag("channel", channel.databaseValue())
                .tag("outcome", outcome)
                .register(registry)
                .record(duration);
    }

    public void transition(String transition, String result) {
        Counter.builder("menu.notification.outbox.transitions")
                .tag("transition", transition)
                .tag("result", result)
                .register(registry)
                .increment();
    }

    public void pollFailure() {
        Counter.builder("menu.notification.outbox.poll.failures")
                .register(registry)
                .increment();
    }

    public void auditsReconciled(int count) {
        if (count > 0) {
            Counter.builder("menu.notification.delivery.stale.reconciled")
                    .register(registry)
                    .increment(count);
        }
    }

    public void updateBacklog(BacklogSnapshot snapshot, Instant now) {
        pending.set(snapshot.pending());
        processing.set(snapshot.processing());
        failed.set(snapshot.failed());
        oldestPendingSeconds.set(snapshot.oldestAvailableAt() == null
                ? 0
                : Math.max(0, Duration.between(snapshot.oldestAvailableAt(), now).toSeconds()));
    }

    private void registerGauge(String name, String description, AtomicLong value) {
        Gauge.builder(name, value, AtomicLong::get)
                .description(description)
                .strongReference(true)
                .register(registry);
    }
}
