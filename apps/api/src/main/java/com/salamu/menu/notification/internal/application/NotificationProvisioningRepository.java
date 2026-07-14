package com.salamu.menu.notification.internal.application;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.salamu.menu.notification.api.NotificationChannel;
import com.salamu.menu.notification.api.NotificationDestinationSpec;
import com.salamu.menu.notification.api.NotificationDestinationStatus;
import com.salamu.menu.notification.api.OpaqueSecretReference;
import com.salamu.menu.tenancy.api.TenantId;

public interface NotificationProvisioningRepository {

    List<StoredDestination> findAll(TenantId tenantId);

    Optional<StoredDestination> findByNaturalKey(
            TenantId tenantId,
            NotificationChannel channel,
            String displayName);

    Optional<StoredDestination> findByNaturalKeyForUpdate(
            TenantId tenantId,
            NotificationChannel channel,
            String displayName);

    Optional<StoredDestination> findByIdForUpdate(TenantId tenantId, UUID destinationId);

    void insert(UUID destinationId, NotificationDestinationSpec spec, OffsetDateTime now);

    boolean hasDeliveries(TenantId tenantId, UUID destinationId);

    boolean deleteExact(
            TenantId tenantId,
            UUID destinationId,
            NotificationChannel channel,
            String displayName);

    record StoredDestination(
            UUID destinationId,
            TenantId tenantId,
            NotificationChannel channel,
            NotificationDestinationStatus status,
            String displayName,
            OpaqueSecretReference secretReference) {
    }
}
