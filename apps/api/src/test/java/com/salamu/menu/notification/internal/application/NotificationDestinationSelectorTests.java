package com.salamu.menu.notification.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.salamu.menu.notification.api.NotificationChannel;
import com.salamu.menu.notification.api.NotificationDestinationId;
import com.salamu.menu.notification.api.NotificationDestinationStatus;
import com.salamu.menu.notification.internal.application.NotificationDestinationReadRepository.StoredDestination;

class NotificationDestinationSelectorTests {

    private final NotificationDestinationReadRepository repository = mock(NotificationDestinationReadRepository.class);
    private final NotificationDestinationSelector selector = new NotificationDestinationSelector(repository);

    @Test
    void selectsEveryValidActiveDestinationAndIgnoresDisabledOnes() {
        var telegram = destination(1, NotificationChannel.TELEGRAM, NotificationDestinationStatus.ACTIVE,
                "Operations", "secret://application/telegram/operations");
        var email = destination(2, NotificationChannel.EMAIL, NotificationDestinationStatus.ACTIVE,
                "Kitchen", "vault://application/email/kitchen");
        var disabled = destination(3, NotificationChannel.POS, NotificationDestinationStatus.DISABLED,
                "Old", "not-valid-but-disabled");
        when(repository.findAllOrdered()).thenReturn(List.of(telegram, disabled, email));

        assertThat(selector.activeDestinations())
                .extracting(DeliveryDestination::channel)
                .containsExactly(NotificationChannel.TELEGRAM, NotificationChannel.EMAIL);
    }

    @Test
    void failsClosedForNoMalformedOrAmbiguousActiveConfiguration() {
        when(repository.findAllOrdered()).thenReturn(List.of());
        assertThatThrownBy(selector::activeDestinations)
                .isInstanceOf(NotificationDestinationSelector.DestinationConfigurationException.class)
                .hasMessage("DESTINATION_NOT_CONFIGURED");

        when(repository.findAllOrdered()).thenReturn(List.of(destination(
                1, NotificationChannel.EMAIL, NotificationDestinationStatus.ACTIVE, "Kitchen", "raw-secret")));
        assertThatThrownBy(selector::activeDestinations)
                .isInstanceOf(NotificationDestinationSelector.DestinationConfigurationException.class)
                .hasMessage("DESTINATION_CONFIGURATION_INVALID");

        when(repository.findAllOrdered()).thenReturn(List.of(
                destination(1, NotificationChannel.EMAIL, NotificationDestinationStatus.ACTIVE,
                        "Kitchen", "secret://application/email/one"),
                destination(2, NotificationChannel.EMAIL, NotificationDestinationStatus.ACTIVE,
                        "Kitchen", "secret://application/email/two")));
        assertThatThrownBy(selector::activeDestinations)
                .isInstanceOf(NotificationDestinationSelector.DestinationConfigurationException.class)
                .hasMessage("DESTINATION_CONFIGURATION_INVALID");
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
