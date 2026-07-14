package com.salamu.menu.tenancy.internal.application.provisioning;

import java.time.OffsetDateTime;
import java.util.Optional;

import com.salamu.menu.tenancy.api.SuspendedTenantSpec;
import com.salamu.menu.tenancy.api.TenantConfigurationSnapshot;
import com.salamu.menu.tenancy.api.TenantId;
import com.salamu.menu.tenancy.api.TenantLifecycle;
import com.salamu.menu.tenancy.api.TenantProvisioningState.ConfigurationPresence;

public interface TenantProvisioningRepository {

    Optional<StoredTenant> findBySlug(String slug);

    Optional<StoredTenant> findById(TenantId tenantId);

    Optional<StoredTenant> findBySlugForUpdate(String slug);

    Optional<StoredTenant> findByIdForUpdate(TenantId tenantId);

    void insertSuspended(TenantId tenantId, SuspendedTenantSpec spec, OffsetDateTime now);

    StoredConfiguration readConfiguration(StoredTenant tenant);

    void insertConfiguration(TenantConfigurationSnapshot snapshot, OffsetDateTime now);

    boolean configurationAssetsMatch(TenantConfigurationSnapshot snapshot);

    void activate(TenantId tenantId, OffsetDateTime now);

    void deleteConfiguration(TenantId tenantId);

    boolean hasTenancyOwnedReferences(TenantId tenantId);

    boolean deleteSuspendedTenant(TenantId tenantId);

    record StoredTenant(TenantId tenantId, SuspendedTenantSpec identity, TenantLifecycle lifecycle) {
    }

    record StoredConfiguration(
            ConfigurationPresence presence,
            TenantConfigurationSnapshot snapshot) {
    }
}
