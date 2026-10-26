package com.salamu.menu.notification.internal.application;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.salamu.menu.notification.api.NotificationChannel;
import com.salamu.menu.notification.api.NotificationDestinationQuery;
import com.salamu.menu.notification.api.NotificationDestinationReadiness;
import com.salamu.menu.notification.api.NotificationDestinationStatus;
import com.salamu.menu.notification.internal.application.NotificationDestinationReadRepository.StoredDestination;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationDestinationQueryService implements NotificationDestinationQuery {

    private final NotificationDestinationReadRepository repository;

    public NotificationDestinationQueryService(NotificationDestinationReadRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<NotificationDestinationReadiness> configured() {
        List<StoredDestination> stored = List.copyOf(repository.findAllOrdered());
        Map<DestinationKey, Integer> activeCounts = new HashMap<>();
        for (StoredDestination destination : stored) {
            if (destination.status() == NotificationDestinationStatus.ACTIVE) {
                activeCounts.merge(key(destination), 1, Integer::sum);
            }
        }
        return stored.stream()
                .map(destination -> readiness(destination, activeCounts))
                .toList();
    }

    private static NotificationDestinationReadiness readiness(
            StoredDestination destination,
            Map<DestinationKey, Integer> activeCounts) {
        boolean active = destination.status() == NotificationDestinationStatus.ACTIVE;
        boolean uniqueActive = activeCounts.getOrDefault(key(destination), 0) == 1;
        boolean ready = active && uniqueActive && destination.secretReference().valid();
        return new NotificationDestinationReadiness(
                destination.id(),
                destination.channel(),
                destination.displayName(),
                destination.status(),
                destination.secretReference().fingerprint(),
                ready);
    }

    private static DestinationKey key(StoredDestination destination) {
        return new DestinationKey(destination.channel(), destination.displayName());
    }

    private record DestinationKey(NotificationChannel channel, String displayName) {
    }
}
