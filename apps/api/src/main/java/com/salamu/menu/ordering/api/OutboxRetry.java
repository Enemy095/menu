package com.salamu.menu.ordering.api;

import java.time.Instant;
import java.util.Objects;

public record OutboxRetry(
        OutboxEventId eventId,
        String workerId,
        int attemptNumber,
        Instant failedAt,
        Instant availableAt,
        String errorCode) {

    public OutboxRetry {
        Objects.requireNonNull(eventId, "eventId");
        workerId = OutboxValues.workerId(workerId);
        if (attemptNumber < 1) {
            throw new IllegalArgumentException("outbox attempt number must be positive");
        }
        Objects.requireNonNull(failedAt, "failedAt");
        if (availableAt == null || availableAt.isBefore(failedAt)) {
            throw new IllegalArgumentException("outbox retry time is invalid");
        }
        errorCode = OutboxValues.errorCode(errorCode);
    }

    @Override
    public String toString() {
        return "OutboxRetry[errorCode=" + errorCode + "]";
    }
}
