package com.salamu.menu.ordering.api;

import java.time.Instant;
import java.util.Objects;

public record OutboxFailure(
        OutboxEventId eventId,
        String workerId,
        int attemptNumber,
        Instant failedAt,
        String errorCode) {

    public OutboxFailure {
        Objects.requireNonNull(eventId, "eventId");
        workerId = OutboxValues.workerId(workerId);
        if (attemptNumber < 1) {
            throw new IllegalArgumentException("outbox attempt number must be positive");
        }
        Objects.requireNonNull(failedAt, "failedAt");
        errorCode = OutboxValues.errorCode(errorCode);
    }

    @Override
    public String toString() {
        return "OutboxFailure[errorCode=" + errorCode + "]";
    }
}
