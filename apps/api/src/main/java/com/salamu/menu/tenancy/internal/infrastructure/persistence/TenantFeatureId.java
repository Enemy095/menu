package com.salamu.menu.tenancy.internal.infrastructure.persistence;

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

@Embeddable
final class TenantFeatureId implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "feature_key", nullable = false, length = 80)
    private String featureKey;

    protected TenantFeatureId() {
    }

    TenantFeatureId(UUID tenantId, String featureKey) {
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.featureKey = Objects.requireNonNull(featureKey, "featureKey");
    }

    UUID tenantId() {
        return tenantId;
    }

    String featureKey() {
        return featureKey;
    }

    @Override
    public boolean equals(Object candidate) {
        return this == candidate
                || candidate instanceof TenantFeatureId other
                && tenantId.equals(other.tenantId)
                && featureKey.equals(other.featureKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tenantId, featureKey);
    }
}
