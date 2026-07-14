package com.salamu.menu.ordering.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ClaimedOutboxEvent(
        OutboxEventId eventId,
        UUID aggregateId,
        String eventType,
        int schemaVersion,
        OutboxPayload payload,
        int attemptNumber,
        Instant createdAt) {

    public ClaimedOutboxEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(aggregateId, "aggregateId");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(createdAt, "createdAt");
        if (eventType == null || !eventType.matches("^[a-z][a-z0-9]*(?:\\.[a-z0-9]+)+$")
                || eventType.length() > 120 || schemaVersion < 1 || attemptNumber < 1) {
            throw new IllegalArgumentException("claimed outbox event is invalid");
        }
    }

    @Override
    public String toString() {
        return "ClaimedOutboxEvent[eventType=" + eventType
                + ",schemaVersion=" + schemaVersion
                + ",attemptNumber=" + attemptNumber + "]";
    }
}
