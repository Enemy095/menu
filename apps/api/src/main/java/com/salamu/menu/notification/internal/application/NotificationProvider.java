package com.salamu.menu.notification.internal.application;

import java.time.Duration;
import java.util.Objects;

import com.salamu.menu.notification.api.NotificationChannel;

/** External provider boundary. Implementations must honor the supplied timeout. */
public interface NotificationProvider {

    NotificationChannel channel();

    ProviderResult send(ProviderRequest request);

    final class ProviderRequest {

        private final String eventType;
        private final int schemaVersion;
        private final OpaqueEventPayload payload;
        private final OpaqueSecretReference secretReference;
        private final String idempotencyKey;
        private final Duration timeout;

        public ProviderRequest(
                String eventType,
                int schemaVersion,
                OpaqueEventPayload payload,
                OpaqueSecretReference secretReference,
                String idempotencyKey,
                Duration timeout) {
            this.eventType = Objects.requireNonNull(eventType, "eventType");
            if (schemaVersion < 1) {
                throw new IllegalArgumentException("Schema version must be positive");
            }
            this.schemaVersion = schemaVersion;
            this.payload = Objects.requireNonNull(payload, "payload");
            this.secretReference = Objects.requireNonNull(secretReference, "secretReference");
            if (idempotencyKey == null || !idempotencyKey.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Provider idempotency key must be a SHA-256 value");
            }
            this.idempotencyKey = idempotencyKey;
            this.timeout = Objects.requireNonNull(timeout, "timeout");
            if (timeout.isZero() || timeout.isNegative()) {
                throw new IllegalArgumentException("Provider timeout must be positive");
            }
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

        public OpaqueSecretReference secretReference() {
            return secretReference;
        }

        public String idempotencyKey() {
            return idempotencyKey;
        }

        public Duration timeout() {
            return timeout;
        }

        @Override
        public String toString() {
            return "ProviderRequest[eventType=" + eventType
                    + ", schemaVersion=" + schemaVersion
                    + ", payload=redacted, secretReference=redacted, idempotencyKey=redacted]";
        }
    }

    final class ProviderResult {

        public enum Outcome {
            SUCCEEDED,
            TRANSIENT_FAILURE,
            PERMANENT_FAILURE
        }

        private final Outcome outcome;
        private final String providerMessageId;
        private final String errorCode;

        private ProviderResult(Outcome outcome, String providerMessageId, String errorCode) {
            this.outcome = Objects.requireNonNull(outcome, "outcome");
            this.providerMessageId = providerMessageId;
            this.errorCode = errorCode;
        }

        public static ProviderResult succeeded(String providerMessageId) {
            if (providerMessageId != null && providerMessageId.length() > 255) {
                throw new IllegalArgumentException("Provider message ID is too long");
            }
            return new ProviderResult(Outcome.SUCCEEDED, providerMessageId, null);
        }

        public static ProviderResult transientFailure(String errorCode) {
            return new ProviderResult(Outcome.TRANSIENT_FAILURE, null, sanitize(errorCode));
        }

        public static ProviderResult permanentFailure(String errorCode) {
            return new ProviderResult(Outcome.PERMANENT_FAILURE, null, sanitize(errorCode));
        }

        public Outcome outcome() {
            return outcome;
        }

        public String providerMessageId() {
            return providerMessageId;
        }

        public String errorCode() {
            return errorCode;
        }

        @Override
        public String toString() {
            return "ProviderResult[outcome=" + outcome
                    + (errorCode == null ? "" : ", errorCode=" + errorCode)
                    + ", providerMessageId=redacted]";
        }

        private static String sanitize(String errorCode) {
            return errorCode != null && errorCode.matches("[A-Z][A-Z0-9_]{0,63}")
                    ? errorCode
                    : "PROVIDER_FAILURE";
        }
    }
}
