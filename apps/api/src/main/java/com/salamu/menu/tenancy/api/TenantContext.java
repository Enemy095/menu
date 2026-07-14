package com.salamu.menu.tenancy.api;

import java.util.Objects;
import java.util.regex.Pattern;

/** Immutable tenant identity established once at the HTTP request boundary. */
public record TenantContext(TenantId tenantId, String slug) {

    private static final Pattern SLUG = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");

    public TenantContext {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(slug, "slug");
        if (slug.length() < 2 || slug.length() > 63 || !SLUG.matcher(slug).matches()) {
            throw new IllegalArgumentException("slug must be a normalized tenant slug");
        }
    }
}
