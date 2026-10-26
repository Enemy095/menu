package com.salamu.menu.ordering.api;

import java.time.Instant;

public record OutboxClaimRequest(
        String workerId,
        Instant now,
        Instant leaseUntil,
        int batchSize,
        int maxAttempts) {

    public OutboxClaimRequest {
        workerId = OutboxValues.workerId(workerId);
        if (now == null || leaseUntil == null || !leaseUntil.isAfter(now)) {
            throw new IllegalArgumentException("outbox lease interval is invalid");
        }
        if (batchSize < 1 || batchSize > 100 || maxAttempts < 1 || maxAttempts > 100) {
            throw new IllegalArgumentException("outbox claim bounds are invalid");
        }
    }
}
