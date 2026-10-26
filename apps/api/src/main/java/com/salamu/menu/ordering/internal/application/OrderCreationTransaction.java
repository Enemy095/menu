package com.salamu.menu.ordering.internal.application;

import java.time.Instant;

import com.salamu.menu.menu.api.CurrentPublishedMenuGuard;
import com.salamu.menu.menu.api.MenuVersionId;
import com.salamu.menu.ordering.api.OrderApiException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderCreationTransaction {

    private final OrderStore store;
    private final CurrentPublishedMenuGuard menuGuard;

    public OrderCreationTransaction(OrderStore store, CurrentPublishedMenuGuard menuGuard) {
        this.store = store;
        this.menuGuard = menuGuard;
    }

    @Transactional
    public TransactionResult create(
            String idempotencyKey,
            String payloadHash,
            PreparedOrder prepared,
            Instant receivedAt) {
        var existing = store.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return new TransactionResult(existing.orElseThrow(), false);
        }
        if (!menuGuard.lockCurrent(new MenuVersionId(prepared.menuVersionId()), prepared.menuVersion())) {
            throw OrderApiException.menuChanged();
        }
        return new TransactionResult(store.insert(idempotencyKey, payloadHash, prepared, receivedAt), true);
    }

    public record TransactionResult(StoredOrder order, boolean created) {
    }
}
