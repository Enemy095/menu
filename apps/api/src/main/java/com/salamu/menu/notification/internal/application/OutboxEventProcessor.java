package com.salamu.menu.notification.internal.application;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Set;

import com.salamu.menu.notification.api.NotificationDestinationId;
import com.salamu.menu.notification.internal.application.NotificationDeliveryAuditService.DeliveryAttempt;
import com.salamu.menu.notification.internal.application.NotificationOutboxGateway.LeaseCompletion;
import com.salamu.menu.notification.internal.application.NotificationOutboxGateway.LeaseFailure;
import com.salamu.menu.notification.internal.application.NotificationOutboxGateway.LeaseRetry;
import com.salamu.menu.notification.internal.application.NotificationOutboxGateway.TransitionResult;
import com.salamu.menu.notification.internal.application.NotificationProvider.ProviderRequest;
import com.salamu.menu.notification.internal.application.NotificationProvider.ProviderResult;
import com.salamu.menu.notification.internal.infrastructure.config.WorkerProperties;
import com.salamu.menu.notification.internal.infrastructure.observability.NotificationWorkerMetrics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "app.worker", name = "enabled", havingValue = "true")
public class OutboxEventProcessor {

    private static final Logger LOGGER = LoggerFactory.getLogger(OutboxEventProcessor.class);
    private static final String SUPPORTED_EVENT = "order.created";
    private static final int SUPPORTED_SCHEMA = 1;

    private final NotificationOutboxGateway outbox;
    private final NotificationDestinationSelector destinationSelector;
    private final NotificationDeliveryAuditService deliveryAudit;
    private final NotificationProviderRegistry providerRegistry;
    private final RetryPolicy retryPolicy;
    private final WorkerProperties properties;
    private final Clock clock;
    private final NotificationWorkerMetrics metrics;

    public OutboxEventProcessor(
            NotificationOutboxGateway outbox,
            NotificationDestinationSelector destinationSelector,
            NotificationDeliveryAuditService deliveryAudit,
            NotificationProviderRegistry providerRegistry,
            RetryPolicy retryPolicy,
            WorkerProperties properties,
            @Qualifier("notificationWorkerClock") Clock clock,
            NotificationWorkerMetrics metrics) {
        this.outbox = outbox;
        this.destinationSelector = destinationSelector;
        this.deliveryAudit = deliveryAudit;
        this.providerRegistry = providerRegistry;
        this.retryPolicy = retryPolicy;
        this.properties = properties;
        this.clock = clock;
        this.metrics = metrics;
    }

    /** Intentionally not transactional: the provider call must never hold a DB transaction. */
    public void process(ClaimedNotificationEvent event) {
        if (!SUPPORTED_EVENT.equals(event.eventType()) || event.schemaVersion() != SUPPORTED_SCHEMA) {
            fail(event, "UNSUPPORTED_EVENT");
            return;
        }

        final java.util.List<DeliveryDestination> destinations;
        try {
            destinations = destinationSelector.activeDestinations();
        } catch (NotificationDestinationSelector.DestinationConfigurationException exception) {
            fail(event, exception.errorCode());
            return;
        }
        if (destinations.size() > properties.maxDestinations()) {
            fail(event, "DESTINATION_CONFIGURATION_INVALID");
            return;
        }

        final Set<NotificationDestinationId> alreadySucceeded;
        try {
            alreadySucceeded = deliveryAudit.succeededDestinations(event.eventId());
        } catch (RuntimeException exception) {
            retryOrExhaust(event, "DELIVERY_AUDIT_UNAVAILABLE");
            return;
        }

        boolean transientFailure = false;
        boolean permanentFailure = false;
        boolean auditUnavailable = false;
        String transientCode = "PROVIDER_FAILURE";
        String permanentCode = "PROVIDER_FAILURE";

        int remainingCalls = (int) destinations.stream()
                .filter(destination -> !alreadySucceeded.contains(destination.id()))
                .count();
        for (DeliveryDestination destination : destinations) {
            if (alreadySucceeded.contains(destination.id())) {
                continue;
            }
            if (!hasLeaseBudget(event, remainingCalls)) {
                retryOrExhaust(event, "LEASE_BUDGET_EXHAUSTED");
                return;
            }
            remainingCalls--;

            DeliveryAttempt attempt;
            try {
                attempt = deliveryAudit.start(
                        event.eventId(),
                        destination.id(),
                        event.attemptNumber(),
                        clock.instant());
            } catch (RuntimeException exception) {
                transientFailure = true;
                auditUnavailable = true;
                transientCode = "DELIVERY_AUDIT_UNAVAILABLE";
                break;
            }

            var provider = providerRegistry.providerFor(destination.channel());
            if (provider.isEmpty()) {
                if (!auditFailure(attempt, "PROVIDER_NOT_CONFIGURED")) {
                    transientFailure = true;
                    auditUnavailable = true;
                    transientCode = "DELIVERY_AUDIT_UNAVAILABLE";
                    break;
                }
                permanentFailure = true;
                permanentCode = "PROVIDER_NOT_CONFIGURED";
                continue;
            }

            ProviderResult result;
            long startedNanos = System.nanoTime();
            try {
                result = provider.get().send(new ProviderRequest(
                        event.eventType(),
                        event.schemaVersion(),
                        event.payload(),
                        destination.secretReference(),
                        providerIdempotencyKey(event, destination),
                        properties.providerTimeout()));
                if (result == null) {
                    result = ProviderResult.transientFailure("PROVIDER_INVALID_RESPONSE");
                }
            } catch (RuntimeException exception) {
                result = ProviderResult.transientFailure("PROVIDER_UNAVAILABLE");
            }
            metrics.providerCall(
                    destination.channel(),
                    metricOutcome(result),
                    Duration.ofNanos(Math.max(0, System.nanoTime() - startedNanos)));

            if (result.outcome() == ProviderResult.Outcome.SUCCEEDED) {
                try {
                    deliveryAudit.succeed(attempt, result.providerMessageId(), clock.instant());
                } catch (RuntimeException exception) {
                    transientFailure = true;
                    auditUnavailable = true;
                    transientCode = "DELIVERY_AUDIT_UNAVAILABLE";
                    break;
                }
            } else {
                String errorCode = result.errorCode();
                if (!auditFailure(attempt, errorCode)) {
                    transientFailure = true;
                    auditUnavailable = true;
                    transientCode = "DELIVERY_AUDIT_UNAVAILABLE";
                    break;
                }
                if (result.outcome() == ProviderResult.Outcome.PERMANENT_FAILURE) {
                    permanentFailure = true;
                    permanentCode = errorCode;
                } else {
                    transientFailure = true;
                    transientCode = errorCode;
                }
            }
        }

        if (auditUnavailable) {
            retryOrExhaust(event, "DELIVERY_AUDIT_UNAVAILABLE");
        } else if (permanentFailure) {
            fail(event, permanentCode);
        } else if (transientFailure) {
            retryOrExhaust(event, transientCode);
        } else {
            complete(event);
        }
    }

    private boolean auditFailure(DeliveryAttempt attempt, String errorCode) {
        try {
            deliveryAudit.fail(attempt, errorCode, clock.instant());
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private boolean hasLeaseBudget(ClaimedNotificationEvent event, int remainingCalls) {
        Instant requiredUntil = clock.instant().plus(properties.providerTimeout().multipliedBy(remainingCalls));
        return requiredUntil.isBefore(event.leasedUntil());
    }

    private void complete(ClaimedNotificationEvent event) {
        TransitionResult result = outbox.complete(new LeaseCompletion(
                event.eventId(), properties.workerId(), event.attemptNumber(), clock.instant()));
        recordTransition("complete", result);
    }

    private void retryOrExhaust(ClaimedNotificationEvent event, String errorCode) {
        if (event.attemptNumber() >= properties.maxAttempts()) {
            fail(event, "ATTEMPTS_EXHAUSTED");
            return;
        }
        Instant failedAt = clock.instant();
        Instant availableAt = failedAt.plus(retryPolicy.delayAfter(event.attemptNumber()));
        TransitionResult result = outbox.retry(new LeaseRetry(
                event.eventId(),
                properties.workerId(),
                event.attemptNumber(),
                availableAt,
                errorCode,
                failedAt));
        recordTransition("retry", result);
    }

    private void fail(ClaimedNotificationEvent event, String errorCode) {
        TransitionResult result = outbox.fail(new LeaseFailure(
                event.eventId(),
                properties.workerId(),
                event.attemptNumber(),
                errorCode,
                clock.instant()));
        recordTransition("dead_letter", result);
    }

    private void recordTransition(String transition, TransitionResult result) {
        metrics.transition(transition, result == TransitionResult.APPLIED ? "applied" : "lease_lost");
        if (result == TransitionResult.LEASE_LOST) {
            LOGGER.atWarn()
                    .addKeyValue("code", "OUTBOX_LEASE_LOST")
                    .addKeyValue("transition", transition)
                    .log("Notification outbox transition rejected");
        }
    }

    private static String metricOutcome(ProviderResult result) {
        return switch (result.outcome()) {
            case SUCCEEDED -> "succeeded";
            case TRANSIENT_FAILURE -> "transient_failure";
            case PERMANENT_FAILURE -> "permanent_failure";
        };
    }

    private static String providerIdempotencyKey(
            ClaimedNotificationEvent event,
            DeliveryDestination destination) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update("notification-delivery-v1".getBytes(StandardCharsets.UTF_8));
            digest.update(uuidBytes(event.eventId()));
            digest.update(uuidBytes(destination.id().value()));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static byte[] uuidBytes(java.util.UUID value) {
        return ByteBuffer.allocate(16)
                .putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits())
                .array();
    }
}
