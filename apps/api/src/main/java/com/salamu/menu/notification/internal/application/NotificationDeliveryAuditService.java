package com.salamu.menu.notification.internal.application;

import java.time.Instant;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;

import com.salamu.menu.notification.api.NotificationDestinationId;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationDeliveryAuditService {

    private static final String ABANDONED_CODE = "LEASE_EXPIRED_UNKNOWN_OUTCOME";

    private final NotificationDeliveryAuditRepository repository;

    public NotificationDeliveryAuditService(NotificationDeliveryAuditRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public Set<NotificationDestinationId> succeededDestinations(UUID eventId) {
        return Set.copyOf(repository.succeededDestinations(eventId));
    }

    @Transactional
    public DeliveryAttempt start(
            UUID eventId,
            NotificationDestinationId destinationId,
            int attemptNumber,
            Instant startedAt) {
        repository.closeAbandonedStarted(
                eventId,
                destinationId,
                attemptNumber,
                startedAt,
                ABANDONED_CODE);
        var attempt = new DeliveryAttempt(UUID.randomUUID(), destinationId, attemptNumber);
        repository.insertStarted(attempt.deliveryId(), eventId, destinationId, attemptNumber, startedAt);
        return attempt;
    }

    @Transactional
    public void succeed(DeliveryAttempt attempt, String providerMessageId, Instant completedAt) {
        requireSingleUpdate(repository.markSucceeded(attempt.deliveryId(), providerMessageId, completedAt));
    }

    @Transactional
    public void fail(DeliveryAttempt attempt, String errorCode, Instant completedAt) {
        requireSafeCode(errorCode);
        requireSingleUpdate(repository.markFailed(attempt.deliveryId(), errorCode, completedAt));
    }

    @Transactional
    public int reconcileStaleStarted(Instant now, Duration leaseDuration, int limit) {
        if (now == null || leaseDuration == null || leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("Stale delivery reconciliation time is invalid");
        }
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("Stale delivery reconciliation limit is invalid");
        }
        return repository.closeStaleStarted(
                now.minus(leaseDuration),
                limit,
                now,
                ABANDONED_CODE);
    }

    private static void requireSingleUpdate(int changedRows) {
        if (changedRows != 1) {
            throw new DeliveryAuditStateException();
        }
    }

    private static void requireSafeCode(String code) {
        if (code == null || !code.matches("[A-Z][A-Z0-9_]{0,63}")) {
            throw new IllegalArgumentException("Delivery error code must be sanitized");
        }
    }

    public record DeliveryAttempt(UUID deliveryId, NotificationDestinationId destinationId, int attemptNumber) {

        public DeliveryAttempt {
            if (deliveryId == null || destinationId == null || attemptNumber < 1) {
                throw new IllegalArgumentException("Delivery attempt is invalid");
            }
        }

        @Override
        public String toString() {
            return "DeliveryAttempt[attemptNumber=" + attemptNumber + "]";
        }
    }

    public static final class DeliveryAuditStateException extends RuntimeException {

        DeliveryAuditStateException() {
            super("DELIVERY_AUDIT_STATE_CONFLICT");
        }
    }
}
