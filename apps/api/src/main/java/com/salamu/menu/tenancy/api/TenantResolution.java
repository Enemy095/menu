package com.salamu.menu.tenancy.api;

import java.util.Objects;
import java.util.Optional;

/** Trusted result of resolving an effective host and optional platform path slug. */
public record TenantResolution(
        TenantContext context,
        String effectiveHost,
        Optional<String> requestedSlug,
        Source source) {

    public TenantResolution {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(effectiveHost, "effectiveHost");
        requestedSlug = requestedSlug == null ? Optional.empty() : requestedSlug;
        Objects.requireNonNull(source, "source");
        if (effectiveHost.isBlank() || effectiveHost.length() > 253) {
            throw new IllegalArgumentException("effectiveHost must be a normalized hostname");
        }
    }

    public enum Source {
        DOMAIN,
        PLATFORM_PATH,
        LOCAL_PATH
    }
}
