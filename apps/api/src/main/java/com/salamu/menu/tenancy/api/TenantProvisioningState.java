package com.salamu.menu.tenancy.api;

import java.util.Objects;

/** Sanitized, immutable inspection result for an active or suspended tenant. */
public record TenantProvisioningState(
        TenantId tenantId,
        SuspendedTenantSpec identity,
        TenantLifecycle lifecycle,
        ConfigurationPresence configurationPresence,
        TenantConfigurationSnapshot configuration,
        TenantSemanticHash configurationHash) {

    public TenantProvisioningState {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(lifecycle, "lifecycle");
        Objects.requireNonNull(configurationPresence, "configurationPresence");
        if (lifecycle == TenantLifecycle.DELETED) {
            throw new IllegalArgumentException("Deleted tenants are not inspectable provisioning state");
        }
        if (configurationPresence == ConfigurationPresence.COMPLETE) {
            Objects.requireNonNull(configuration, "configuration");
            Objects.requireNonNull(configurationHash, "configurationHash");
            if (!configuration.tenantId().equals(tenantId)
                    || !configuration.identity().equals(identity)) {
                throw new IllegalArgumentException("Configuration identity does not match tenant state");
            }
        } else if (configuration != null || configurationHash != null) {
            throw new IllegalArgumentException("Incomplete configuration must not expose a snapshot or hash");
        }
    }

    public enum ConfigurationPresence {
        ABSENT,
        PARTIAL,
        COMPLETE
    }
}
