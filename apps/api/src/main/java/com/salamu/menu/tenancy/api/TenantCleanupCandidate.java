package com.salamu.menu.tenancy.api;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Ledger-backed exact candidate for cleanup. A null expected hash means the run owns only an empty skeleton.
 */
public record TenantCleanupCandidate(
        TenantId tenantId,
        String slug,
        TenantSemanticHash expectedConfigurationHash) {

    private static final Pattern SLUG = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");

    public TenantCleanupCandidate {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(slug, "slug");
        if (slug.length() > 63 || !SLUG.matcher(slug).matches()) {
            throw new IllegalArgumentException("slug has an invalid canonical value");
        }
    }
}
