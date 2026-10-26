package com.salamu.menu.ordering.internal.application;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record StoredOrder(
        UUID orderId,
        long orderNumber,
        String idempotencyPayloadHash,
        String status,
        long serverTotalMinor,
        String currency,
        Instant serverReceivedAt,
        boolean needsConfirmation) {

    public StoredOrder {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(idempotencyPayloadHash, "idempotencyPayloadHash");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(currency, "currency");
    }
}
