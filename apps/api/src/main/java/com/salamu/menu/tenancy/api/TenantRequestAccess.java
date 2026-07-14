package com.salamu.menu.tenancy.api;

import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;

/** Request-scoped access to the trusted resolution established by the tenancy filter. */
public interface TenantRequestAccess {

    Optional<TenantResolution> resolution(HttpServletRequest request);

    default Optional<TenantContext> context(HttpServletRequest request) {
        return resolution(request).map(TenantResolution::context);
    }
}
