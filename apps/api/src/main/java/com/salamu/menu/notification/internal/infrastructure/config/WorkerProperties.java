package com.salamu.menu.notification.internal.infrastructure.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.worker")
public record WorkerProperties(
        boolean enabled,
        String workerId,
        Duration pollInterval,
        int batchSize,
        int concurrency,
        int maxDestinations,
        Duration leaseDuration,
        Duration providerTimeout,
        int maxAttempts,
        Duration initialBackoff,
        Duration maxBackoff,
        double jitterRatio,
        Duration shutdownTimeout) {

    public WorkerProperties {
        if (workerId == null || workerId.isBlank() || workerId.length() > 120) {
            throw new IllegalArgumentException("app.worker.worker-id must be present and at most 120 characters");
        }
        requirePositive(pollInterval, "poll-interval");
        requirePositive(leaseDuration, "lease-duration");
        requirePositive(providerTimeout, "provider-timeout");
        requirePositive(initialBackoff, "initial-backoff");
        requirePositive(maxBackoff, "max-backoff");
        requirePositive(shutdownTimeout, "shutdown-timeout");
        if (batchSize < 1 || batchSize > 100) {
            throw new IllegalArgumentException("app.worker.batch-size must be between 1 and 100");
        }
        if (concurrency < 1 || concurrency > 32) {
            throw new IllegalArgumentException("app.worker.concurrency must be between 1 and 32");
        }
        if (maxDestinations < 1 || maxDestinations > 16) {
            throw new IllegalArgumentException("app.worker.max-destinations must be between 1 and 16");
        }
        if (maxAttempts < 1 || maxAttempts > 100) {
            throw new IllegalArgumentException("app.worker.max-attempts must be between 1 and 100");
        }
        if (providerTimeout.compareTo(leaseDuration) >= 0) {
            throw new IllegalArgumentException("app.worker.provider-timeout must be shorter than lease-duration");
        }
        if (providerTimeout.multipliedBy(maxDestinations).compareTo(leaseDuration) >= 0) {
            throw new IllegalArgumentException(
                    "app.worker lease-duration must exceed the total bounded provider timeout");
        }
        if (initialBackoff.compareTo(maxBackoff) > 0) {
            throw new IllegalArgumentException("app.worker.initial-backoff cannot exceed max-backoff");
        }
        if (jitterRatio < 0 || jitterRatio > 0.5 || !Double.isFinite(jitterRatio)) {
            throw new IllegalArgumentException("app.worker.jitter-ratio must be between 0 and 0.5");
        }
    }

    private static void requirePositive(Duration value, String property) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("app.worker." + property + " must be positive");
        }
    }
}
