package com.salamu.menu.tenancy.api;

/** Raised before storage access when imported content violates the immutable asset policy. */
public final class InvalidTenantAssetException extends IllegalArgumentException {

    public InvalidTenantAssetException(String message) {
        super(message);
    }
}
