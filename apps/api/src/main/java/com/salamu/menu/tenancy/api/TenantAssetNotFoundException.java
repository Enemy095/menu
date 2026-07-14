package com.salamu.menu.tenancy.api;

/** Deliberately does not distinguish an unknown tenant from a cross-tenant asset lookup. */
public final class TenantAssetNotFoundException extends RuntimeException {

    public TenantAssetNotFoundException() {
        super("Tenant asset not found");
    }
}
