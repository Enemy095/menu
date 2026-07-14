package com.salamu.menu.tenancy.api;

/**
 * Fail-closed conflict raised by the non-HTTP asset provisioning boundary.
 *
 * <p>The message intentionally contains no object key, storage response, or run fingerprint.
 */
public final class TenantAssetProvisioningConflictException extends RuntimeException {

    public TenantAssetProvisioningConflictException(String message) {
        super(message);
    }

    public TenantAssetProvisioningConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
