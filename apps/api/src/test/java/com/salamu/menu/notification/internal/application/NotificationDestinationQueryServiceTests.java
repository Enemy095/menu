package com.salamu.menu.notification.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.salamu.menu.notification.api.NotificationChannel;
import com.salamu.menu.notification.api.NotificationDestinationId;
import com.salamu.menu.notification.api.NotificationDestinationStatus;
import com.salamu.menu.notification.internal.application.NotificationDestinationReadRepository.StoredDestination;

class NotificationDestinationQueryServiceTests {

    private static final String RAW_REFERENCE = "secret://application/providers/telegram-bot";

    private final NotificationDestinationReadRepository repository = mock(NotificationDestinationReadRepository.class);
    private final NotificationDestinationQueryService query = new NotificationDestinationQueryService(repository);

    @Test
    void exposesOnlySanitizedReadinessAndAStableNonReversibleFingerprint() {
        var stored = destination(1, NotificationChannel.TELEGRAM, NotificationDestinationStatus.ACTIVE,
                "Primary", RAW_REFERENCE);
        when(repository.findAllOrdered()).thenReturn(List.of(stored));

        var result = query.configured();

        assertThat(result).singleElement().satisfies(readiness -> {
            assertThat(readiness.id()).isEqualTo(stored.id());
            assertThat(readiness.channel()).isEqualTo(NotificationChannel.TELEGRAM);
            assertThat(readiness.displayName()).isEqualTo("Primary");
            assertThat(readiness.status()).isEqualTo(NotificationDestinationStatus.ACTIVE);
            assertThat(readiness.referenceFingerprint()).matches("[0-9a-f]{64}");
            assertThat(readiness.referenceFingerprint()).doesNotContain(RAW_REFERENCE);
            assertThat(readiness.ready()).isTrue();
        });
        assertThat(result.toString()).doesNotContain(RAW_REFERENCE);
        verify(repository).findAllOrdered();
    }

    @Test
    void malformedDisabledAndDuplicateActiveDestinationsFailReadinessWithoutFallback() {
        var malformed = destination(1, NotificationChannel.EMAIL, NotificationDestinationStatus.ACTIVE,
                "Malformed", "https://user:password@example.test/hook");
        var disabled = destination(2, NotificationChannel.POS, NotificationDestinationStatus.DISABLED,
                "Disabled", "vault://application/pos/primary");
        var duplicateOne = destination(3, NotificationChannel.WHATSAPP, NotificationDestinationStatus.ACTIVE,
                "Operations", "secret://application/whatsapp/one");
        var duplicateTwo = destination(4, NotificationChannel.WHATSAPP, NotificationDestinationStatus.ACTIVE,
                "Operations", "secret://application/whatsapp/two");
        when(repository.findAllOrdered()).thenReturn(List.of(malformed, disabled, duplicateOne, duplicateTwo));

        var result = query.configured();

        assertThat(result).hasSize(4).allMatch(readiness -> !readiness.ready());
        assertThat(result).extracting(value -> value.id().value())
                .containsExactly(
                        malformed.id().value(),
                        disabled.id().value(),
                        duplicateOne.id().value(),
                        duplicateTwo.id().value());
    }

    @Test
    void opaqueReferenceNeverRendersItsRawValueEvenWhenInvalid() {
        var valid = new OpaqueSecretReference(RAW_REFERENCE);
        var invalid = new OpaqueSecretReference(" secret://application/private/value ");

        assertThat(valid.valid()).isTrue();
        assertThat(invalid.valid()).isFalse();
        assertThat(valid.toString()).isEqualTo("OpaqueSecretReference[redacted]");
        assertThat(invalid.toString()).isEqualTo("OpaqueSecretReference[redacted]");
        assertThat(valid.toString()).doesNotContain(RAW_REFERENCE);
        assertThat(valid.fingerprint()).isEqualTo(new OpaqueSecretReference(RAW_REFERENCE).fingerprint());
        assertThat(valid.fingerprint()).isNotEqualTo(invalid.fingerprint());
    }

    private static StoredDestination destination(
            long suffix,
            NotificationChannel channel,
            NotificationDestinationStatus status,
            String displayName,
            String reference) {
        return new StoredDestination(
                new NotificationDestinationId(new UUID(0, suffix)),
                channel,
                status,
                displayName,
                new OpaqueSecretReference(reference));
    }
}
