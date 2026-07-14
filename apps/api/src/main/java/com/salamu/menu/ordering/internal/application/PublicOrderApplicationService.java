package com.salamu.menu.ordering.internal.application;

import java.time.Clock;
import java.util.Locale;
import java.util.UUID;

import com.salamu.menu.ordering.api.OrderApiException;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

@Service
public class PublicOrderApplicationService {

    private final OrderStore store;
    private final OrderPayloadHasher hasher;
    private final OrderPreparationService preparation;
    private final OrderCreationTransaction transaction;
    private final Clock clock;

    public PublicOrderApplicationService(
            OrderStore store,
            OrderPayloadHasher hasher,
            OrderPreparationService preparation,
            OrderCreationTransaction transaction,
            Clock clock) {
        this.store = store;
        this.hasher = hasher;
        this.preparation = preparation;
        this.transaction = transaction;
        this.clock = clock;
    }

    public CreationResult create(String rawIdempotencyKey, CreateOrderCommand command) {
        String idempotencyKey = idempotencyKey(rawIdempotencyKey);
        String payloadHash = hasher.hash(command);

        var existing = findExisting(idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.orElseThrow(), payloadHash);
        }

        var prepared = preparation.prepare(command);
        try {
            var result = transaction.create(idempotencyKey, payloadHash, prepared, clock.instant());
            if (!result.created()) {
                return replay(result.order(), payloadHash);
            }
            return new CreationResult(result.order(), false);
        } catch (DataIntegrityViolationException exception) {
            var winner = findExisting(idempotencyKey);
            if (winner.isPresent()) {
                return replay(winner.orElseThrow(), payloadHash);
            }
            throw OrderApiException.dependencyUnavailable();
        } catch (DataAccessException exception) {
            throw OrderApiException.dependencyUnavailable();
        }
    }

    private java.util.Optional<StoredOrder> findExisting(String idempotencyKey) {
        try {
            return store.findByIdempotencyKey(idempotencyKey);
        } catch (DataAccessException exception) {
            throw OrderApiException.dependencyUnavailable();
        }
    }

    private static CreationResult replay(StoredOrder order, String payloadHash) {
        if (!order.idempotencyPayloadHash().equals(payloadHash)) {
            throw OrderApiException.idempotencyConflict();
        }
        return new CreationResult(order, true);
    }

    private static String idempotencyKey(String value) {
        if (value == null) {
            throw OrderApiException.validation("Idempotency-Key", "REQUIRED");
        }
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        try {
            UUID parsed = UUID.fromString(normalized);
            if (!parsed.toString().equals(normalized)) {
                throw new IllegalArgumentException();
            }
            return normalized;
        } catch (IllegalArgumentException exception) {
            throw OrderApiException.validation("Idempotency-Key", "INVALID_FORMAT");
        }
    }

    public record CreationResult(StoredOrder order, boolean replayed) {
    }
}
