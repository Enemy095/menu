package com.salamu.menu.notification.internal.application;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Claimed event whose textual form deliberately omits identifiers and payload. */
public final class ClaimedNotificationEvent {

    private final UUID eventId;
    private final String eventType;
    private final int schemaVersion;
    private final OpaqueEventPayload payload;
    private final int attemptNumber;
    private final Instant createdAt;
    private final Instant leasedUntil;

    public ClaimedNotificationEvent(
            UUID eventId,
            String eventType,
            int schemaVersion,
            OpaqueEventPayload payload,
            int attemptNumber,
            Instant createdAt,
            Instant leasedUntil) {
        this.eventId = Objects.requireNonNull(eventId, "eventId");
        if (eventType == null || !eventType.matches("[a-z][a-z0-9]*(\\.[a-z][a-z0-9]*)+")) {
            throw new IllegalArgumentException("Event type must be a bounded dotted identifier");
        }
        if (schemaVersion < 1 || attemptNumber < 1) {
            throw new IllegalArgumentException("Schema version and attempt number must be positive");
        }
        this.eventType = eventType;
        this.schemaVersion = schemaVersion;
        this.payload = Objects.requireNonNull(payload, "payload");
        this.attemptNumber = attemptNumber;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.leasedUntil = Objects.requireNonNull(leasedUntil, "leasedUntil");
    }

    public UUID eventId() {
        return eventId;
    }

    public String eventType() {
        return eventType;
    }

    public int schemaVersion() {
        return schemaVersion;
    }

    public OpaqueEventPayload payload() {
        return payload;
    }

    public int attemptNumber() {
        return attemptNumber;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant leasedUntil() {
        return leasedUntil;
    }

    @Override
    public String toString() {
        return "ClaimedNotificationEvent[eventType=" + eventType
                + ", schemaVersion=" + schemaVersion
                + ", attemptNumber=" + attemptNumber + "]";
    }
}
