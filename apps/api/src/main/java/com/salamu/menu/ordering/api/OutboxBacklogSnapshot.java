package com.salamu.menu.ordering.api;

import java.time.Instant;

public record OutboxBacklogSnapshot(
        long pendingCount,
        long processingCount,
        long failedCount,
        Instant oldestPendingAt) {

    public OutboxBacklogSnapshot {
        if (pendingCount < 0 || processingCount < 0 || failedCount < 0) {
            throw new IllegalArgumentException("outbox backlog counts must be non-negative");
        }
        if (pendingCount == 0 && oldestPendingAt != null) {
            throw new IllegalArgumentException("empty outbox backlog cannot have an oldest event");
        }
    }
}
