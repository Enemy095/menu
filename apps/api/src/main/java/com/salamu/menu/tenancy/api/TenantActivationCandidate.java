package com.salamu.menu.tenancy.api;

import java.util.Objects;
import java.util.regex.Pattern;

/** Exact expected state that may be atomically promoted from suspended to active. */
public record TenantActivationCandidate(
        TenantId tenantId,
        String slug,
        TenantSemanticHash expectedConfigurationHash) {

    private static final Pattern SLUG = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");

    public TenantActivationCandidate {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(slug, "slug");
        if (slug.length() > 63 || !SLUG.matcher(slug).matches()) {
            throw new IllegalArgumentException("slug has an invalid canonical value");
        }
        Objects.requireNonNull(expectedConfigurationHash, "expectedConfigurationHash");
    }
}
