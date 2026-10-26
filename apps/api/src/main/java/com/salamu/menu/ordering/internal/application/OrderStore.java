package com.salamu.menu.ordering.internal.application;

import java.time.Instant;
import java.util.Optional;

public interface OrderStore {

    Optional<StoredOrder> findByIdempotencyKey(String idempotencyKey);

    StoredOrder insert(String idempotencyKey, String payloadHash, PreparedOrder order, Instant receivedAt);
}
