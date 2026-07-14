package com.salamu.menu.tenancy.internal.domain;

import java.util.Objects;

import com.salamu.menu.tenancy.api.TenantContext;
import com.salamu.menu.tenancy.api.TenantId;

public record TenantRegistration(TenantId tenantId, String slug) {

    public TenantRegistration {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(slug, "slug");
    }

    public TenantContext toContext() {
        return new TenantContext(tenantId, slug);
    }
}
