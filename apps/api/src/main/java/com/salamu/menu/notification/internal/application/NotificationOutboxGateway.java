package com.salamu.menu.notification.internal.application;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Notification-side view of the public ordering outbox API. */
public interface NotificationOutboxGateway {

    List<ClaimedNotificationEvent> claim(ClaimRequest request);

    TransitionResult complete(LeaseCompletion completion);

    TransitionResult retry(LeaseRetry retry);

    TransitionResult fail(LeaseFailure failure);

    BacklogSnapshot backlog(Instant now);

    enum TransitionResult {
        APPLIED,
        LEASE_LOST
    }

    record ClaimRequest(String workerId, int limit, int maxAttempts, Instant now, Instant leasedUntil) {

        public ClaimRequest {
            if (workerId == null || workerId.isBlank() || workerId.length() > 120) {
                throw new IllegalArgumentException("Worker ID must be present and bounded");
            }
            if (limit < 1 || maxAttempts < 1) {
                throw new IllegalArgumentException("Claim bounds must be positive");
            }
            Objects.requireNonNull(now, "now");
            Objects.requireNonNull(leasedUntil, "leasedUntil");
            if (!leasedUntil.isAfter(now)) {
                throw new IllegalArgumentException("Lease must end after claim time");
            }
        }
    }

    record LeaseCompletion(UUID eventId, String workerId, int attemptNumber, Instant completedAt) {

        public LeaseCompletion {
            Objects.requireNonNull(eventId, "eventId");
            requireWorker(workerId);
            requireAttempt(attemptNumber);
            Objects.requireNonNull(completedAt, "completedAt");
        }

        @Override
        public String toString() {
            return "LeaseCompletion[redacted]";
        }
    }

    record LeaseRetry(
            UUID eventId,
            String workerId,
            int attemptNumber,
            Instant availableAt,
            String errorCode,
            Instant failedAt) {

        public LeaseRetry {
            Objects.requireNonNull(eventId, "eventId");
            requireWorker(workerId);
            requireAttempt(attemptNumber);
            Objects.requireNonNull(availableAt, "availableAt");
            requireErrorCode(errorCode);
            Objects.requireNonNull(failedAt, "failedAt");
            if (availableAt.isBefore(failedAt)) {
                throw new IllegalArgumentException("Retry availability cannot precede failure time");
            }
        }

        @Override
        public String toString() {
            return "LeaseRetry[errorCode=" + errorCode + "]";
        }
    }

    record LeaseFailure(UUID eventId, String workerId, int attemptNumber, String errorCode, Instant failedAt) {

        public LeaseFailure {
            Objects.requireNonNull(eventId, "eventId");
            requireWorker(workerId);
            requireAttempt(attemptNumber);
            requireErrorCode(errorCode);
            Objects.requireNonNull(failedAt, "failedAt");
        }

        @Override
        public String toString() {
            return "LeaseFailure[errorCode=" + errorCode + "]";
        }
    }

    record BacklogSnapshot(long pending, long processing, long failed, Instant oldestAvailableAt) {

        public BacklogSnapshot {
            if (pending < 0 || processing < 0 || failed < 0) {
                throw new IllegalArgumentException("Backlog counts cannot be negative");
            }
        }
    }

    private static void requireWorker(String workerId) {
        if (workerId == null || workerId.isBlank() || workerId.length() > 120) {
            throw new IllegalArgumentException("Worker ID must be present and bounded");
        }
    }

    private static void requireErrorCode(String errorCode) {
        if (errorCode == null || !errorCode.matches("[A-Z][A-Z0-9_]{0,63}")) {
            throw new IllegalArgumentException("Error code must be sanitized");
        }
    }

    private static void requireAttempt(int attemptNumber) {
        if (attemptNumber < 1) {
            throw new IllegalArgumentException("Attempt number must be positive");
        }
    }
}
