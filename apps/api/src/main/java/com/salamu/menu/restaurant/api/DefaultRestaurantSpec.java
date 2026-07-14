package com.salamu.menu.restaurant.api;

import java.util.Objects;
import java.util.regex.Pattern;

import com.salamu.menu.tenancy.api.TenantId;

/** Immutable semantic input for the one default restaurant owned by a tenant. */
public record DefaultRestaurantSpec(
        TenantId tenantId,
        String tenantSlug,
        String restaurantSlug,
        String displayName) {

    private static final Pattern SLUG = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");

    public DefaultRestaurantSpec {
        Objects.requireNonNull(tenantId, "tenantId");
        tenantSlug = canonicalSlug(tenantSlug, "tenantSlug");
        restaurantSlug = canonicalSlug(restaurantSlug, "restaurantSlug");
        displayName = canonicalText(displayName, 160, "displayName");
    }

    static String canonicalSlug(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.length() > 63 || !SLUG.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " has an invalid canonical value");
        }
        return value;
    }

    static String canonicalText(String value, int maximumLength, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank() || value.length() > maximumLength || !value.equals(value.strip())) {
            throw new IllegalArgumentException(field + " has an invalid canonical value");
        }
        return value;
    }
}
