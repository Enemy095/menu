package com.salamu.menu.notification.internal.application;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.salamu.menu.notification.api.NotificationChannel;
import com.salamu.menu.notification.api.NotificationDestinationStatus;
import com.salamu.menu.notification.internal.application.NotificationDestinationReadRepository.StoredDestination;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationDestinationSelector {

    private final NotificationDestinationReadRepository repository;

    public NotificationDestinationSelector(NotificationDestinationReadRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<DeliveryDestination> activeDestinations() {
        List<StoredDestination> active = repository.findAllOrdered().stream()
                .filter(destination -> destination.status() == NotificationDestinationStatus.ACTIVE)
                .toList();
        if (active.isEmpty()) {
            throw new DestinationConfigurationException("DESTINATION_NOT_CONFIGURED");
        }

        Map<DestinationKey, Integer> counts = new HashMap<>();
        active.forEach(destination -> counts.merge(key(destination), 1, Integer::sum));
        if (active.stream().anyMatch(destination -> !destination.secretReference().valid()
                || counts.getOrDefault(key(destination), 0) != 1)) {
            throw new DestinationConfigurationException("DESTINATION_CONFIGURATION_INVALID");
        }

        return active.stream()
                .map(destination -> new DeliveryDestination(
                        destination.id(),
                        destination.channel(),
                        destination.secretReference()))
                .toList();
    }

    private static DestinationKey key(StoredDestination destination) {
        return new DestinationKey(destination.channel(), destination.displayName());
    }

    private record DestinationKey(NotificationChannel channel, String displayName) {
    }

    public static final class DestinationConfigurationException extends RuntimeException {

        private final String errorCode;

        DestinationConfigurationException(String errorCode) {
            super(errorCode);
            this.errorCode = errorCode;
        }

        public String errorCode() {
            return errorCode;
        }

        @Override
        public String toString() {
            return "DestinationConfigurationException[errorCode=" + errorCode + "]";
        }
    }
}
