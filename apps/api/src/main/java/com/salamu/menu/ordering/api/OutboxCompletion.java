package com.salamu.menu.ordering.api;

import java.time.Instant;
import java.util.Objects;

public record OutboxCompletion(OutboxEventId eventId, String workerId, int attemptNumber, Instant completedAt) {

    public OutboxCompletion {
        Objects.requireNonNull(eventId, "eventId");
        workerId = OutboxValues.workerId(workerId);
        if (attemptNumber < 1) {
            throw new IllegalArgumentException("outbox attempt number must be positive");
        }
        Objects.requireNonNull(completedAt, "completedAt");
    }

    @Override
    public String toString() {
        return "OutboxCompletion[redacted]";
    }
}
