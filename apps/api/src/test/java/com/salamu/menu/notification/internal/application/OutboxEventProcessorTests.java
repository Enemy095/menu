package com.salamu.menu.notification.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.salamu.menu.notification.api.NotificationChannel;
import com.salamu.menu.notification.api.NotificationDestinationId;
import com.salamu.menu.notification.internal.application.NotificationDeliveryAuditService.DeliveryAttempt;
import com.salamu.menu.notification.internal.application.NotificationOutboxGateway.LeaseFailure;
import com.salamu.menu.notification.internal.application.NotificationOutboxGateway.LeaseRetry;
import com.salamu.menu.notification.internal.application.NotificationOutboxGateway.TransitionResult;
import com.salamu.menu.notification.internal.application.NotificationProvider.ProviderResult;
import com.salamu.menu.notification.internal.infrastructure.config.WorkerProperties;
import com.salamu.menu.notification.internal.infrastructure.observability.NotificationWorkerMetrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class OutboxEventProcessorTests {

    private static final Instant NOW = Instant.parse("2026-07-14T12:00:00Z");
    private static final UUID EVENT_ID = new UUID(0, 10);
    private static final NotificationDestinationId DESTINATION_ID =
            new NotificationDestinationId(new UUID(0, 20));

    private final NotificationOutboxGateway outbox = mock(NotificationOutboxGateway.class);
    private final NotificationDestinationSelector selector = mock(NotificationDestinationSelector.class);
    private final NotificationDeliveryAuditService audit = mock(NotificationDeliveryAuditService.class);
    private final DeliveryAttempt deliveryAttempt = new DeliveryAttempt(new UUID(0, 30), DESTINATION_ID, 1);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @BeforeEach
    void commonBehavior() {
        when(outbox.complete(any())).thenReturn(TransitionResult.APPLIED);
        when(outbox.retry(any())).thenReturn(TransitionResult.APPLIED);
        when(outbox.fail(any())).thenReturn(TransitionResult.APPLIED);
        when(selector.activeDestinations()).thenReturn(List.of(destination()));
        when(audit.succeededDestinations(EVENT_ID)).thenReturn(Set.of());
        when(audit.start(EVENT_ID, DESTINATION_ID, 1, NOW)).thenReturn(deliveryAttempt);
    }

    @Test
    void successfulProviderCallIsAuditedAndCompletesTheLease() {
        var provider = new DeterministicFakeNotificationProvider(NotificationChannel.TELEGRAM)
                .then(ProviderResult.succeeded("provider-message-sensitive"));
        processor(provider, 4).process(event(1));

        assertThat(provider.requests()).singleElement().satisfies(request -> {
            assertThat(request.eventType()).isEqualTo("order.created");
            assertThat(request.idempotencyKey()).matches("[0-9a-f]{64}");
            assertThat(request.toString())
                    .doesNotContain("customer-phone", "provider-message-sensitive", EVENT_ID.toString());
        });
        verify(audit).succeed(deliveryAttempt, "provider-message-sensitive", NOW);
        verify(outbox).complete(any());
        verify(outbox, never()).retry(any());
        verify(outbox, never()).fail(any());
    }

    @Test
    void transientFailureUsesDeterministicBackoffAndSanitizedAudit() {
        var provider = new DeterministicFakeNotificationProvider(NotificationChannel.TELEGRAM)
                .then(ProviderResult.transientFailure("REMOTE_TIMEOUT"));
        processor(provider, 4).process(event(1));

        verify(audit).fail(deliveryAttempt, "REMOTE_TIMEOUT", NOW);
        var retry = ArgumentCaptor.forClass(LeaseRetry.class);
        verify(outbox).retry(retry.capture());
        assertThat(retry.getValue().failedAt()).isEqualTo(NOW);
        assertThat(retry.getValue().availableAt()).isEqualTo(NOW.plusSeconds(5));
        assertThat(retry.getValue().errorCode()).isEqualTo("REMOTE_TIMEOUT");
    }

    @Test
    void providerExceptionDoesNotExposeMessageAndExhaustedAttemptDeadLetters() {
        var provider = new DeterministicFakeNotificationProvider(NotificationChannel.TELEGRAM)
                .thenThrow(new IllegalStateException("phone=customer-phone secret://raw"));
        processor(provider, 1).process(event(1));

        verify(audit).fail(deliveryAttempt, "PROVIDER_UNAVAILABLE", NOW);
        var failure = ArgumentCaptor.forClass(LeaseFailure.class);
        verify(outbox).fail(failure.capture());
        assertThat(failure.getValue().errorCode()).isEqualTo("ATTEMPTS_EXHAUSTED");
        assertThat(failure.getValue().toString()).doesNotContain("customer-phone", "secret://raw", EVENT_ID.toString());
    }

    @Test
    void permanentFailureDeadLettersAndAlreadySucceededDestinationIsNotCalledAgain() {
        var permanent = new DeterministicFakeNotificationProvider(NotificationChannel.TELEGRAM)
                .then(ProviderResult.permanentFailure("DESTINATION_REJECTED"));
        processor(permanent, 4).process(event(1));
        verify(outbox).fail(any());

        var untouched = new DeterministicFakeNotificationProvider(NotificationChannel.TELEGRAM)
                .then(ProviderResult.succeeded("unused"));
        when(audit.succeededDestinations(EVENT_ID)).thenReturn(Set.of(DESTINATION_ID));
        processor(untouched, 4).process(event(1));
        assertThat(untouched.requests()).isEmpty();
        verify(outbox).complete(any());
    }

    @Test
    void unsupportedEventFailsBeforeReadingDestinationOrCallingProvider() {
        var provider = new DeterministicFakeNotificationProvider(NotificationChannel.TELEGRAM)
                .then(ProviderResult.succeeded("unused"));
        processor(provider, 4).process(new ClaimedNotificationEvent(
                EVENT_ID,
                "order.cancelled",
                1,
                new OpaqueEventPayload("{\"phone\":\"customer-phone\"}"),
                1,
                NOW.minusSeconds(10),
                NOW.plusSeconds(30)));

        verify(selector, never()).activeDestinations();
        var failure = ArgumentCaptor.forClass(LeaseFailure.class);
        verify(outbox).fail(failure.capture());
        assertThat(failure.getValue().errorCode()).isEqualTo("UNSUPPORTED_EVENT");
        assertThat(provider.requests()).isEmpty();
    }

    @Test
    void insufficientLeaseBudgetRetriesBeforeStartingExternalWork() {
        var provider = new DeterministicFakeNotificationProvider(NotificationChannel.TELEGRAM)
                .then(ProviderResult.succeeded("unused"));
        var shortLease = new ClaimedNotificationEvent(
                EVENT_ID,
                "order.created",
                1,
                new OpaqueEventPayload("{\"phone\":\"customer-phone\"}"),
                1,
                NOW.minusSeconds(10),
                NOW.plusSeconds(5));

        processor(provider, 4).process(shortLease);

        verify(audit, never()).start(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any());
        assertThat(provider.requests()).isEmpty();
        var retry = ArgumentCaptor.forClass(LeaseRetry.class);
        verify(outbox).retry(retry.capture());
        assertThat(retry.getValue().errorCode()).isEqualTo("LEASE_BUDGET_EXHAUSTED");
    }

    @Test
    void tooManyDestinationsFailsClosedBeforeAuditingOrCallingProviders() {
        var provider = new DeterministicFakeNotificationProvider(NotificationChannel.TELEGRAM)
                .then(ProviderResult.succeeded("unused"));
        when(selector.activeDestinations()).thenReturn(List.of(
                destination(),
                new DeliveryDestination(
                        new NotificationDestinationId(new UUID(0, 21)),
                        NotificationChannel.EMAIL,
                        new OpaqueSecretReference("secret://application/email/operations")),
                new DeliveryDestination(
                        new NotificationDestinationId(new UUID(0, 22)),
                        NotificationChannel.POS,
                        new OpaqueSecretReference("secret://application/pos/operations"))));

        processor(provider, 4).process(event(1));

        verify(audit, never()).succeededDestinations(any());
        var failure = ArgumentCaptor.forClass(LeaseFailure.class);
        verify(outbox).fail(failure.capture());
        assertThat(failure.getValue().errorCode()).isEqualTo("DESTINATION_CONFIGURATION_INVALID");
        assertThat(provider.requests()).isEmpty();
    }

    private OutboxEventProcessor processor(DeterministicFakeNotificationProvider provider, int maxAttempts) {
        return new OutboxEventProcessor(
                outbox,
                selector,
                audit,
                new NotificationProviderRegistry(List.of(provider)),
                new RetryPolicy(Duration.ofSeconds(5), Duration.ofMinutes(15), 0, () -> 0),
                properties(maxAttempts),
                clock,
                new NotificationWorkerMetrics(new SimpleMeterRegistry()));
    }

    private static ClaimedNotificationEvent event(int attemptNumber) {
        return new ClaimedNotificationEvent(
                EVENT_ID,
                "order.created",
                1,
                new OpaqueEventPayload("{\"phone\":\"customer-phone\"}"),
                attemptNumber,
                NOW.minusSeconds(10),
                NOW.plusSeconds(30));
    }

    private static DeliveryDestination destination() {
        return new DeliveryDestination(
                DESTINATION_ID,
                NotificationChannel.TELEGRAM,
                new OpaqueSecretReference("secret://application/telegram/operations"));
    }

    private static WorkerProperties properties(int maxAttempts) {
        return new WorkerProperties(
                true,
                "test-worker",
                Duration.ofSeconds(1),
                4,
                2,
                2,
                Duration.ofSeconds(30),
                Duration.ofSeconds(5),
                maxAttempts,
                Duration.ofSeconds(5),
                Duration.ofMinutes(15),
                0,
                Duration.ofSeconds(5));
    }
}
