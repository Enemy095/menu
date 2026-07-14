package com.salamu.menu.tenancy.internal.application.asset;

public final class AssetRegistrationConflictException extends RuntimeException {

    public AssetRegistrationConflictException(Throwable cause) {
        super("Concurrent asset registration conflict", cause);
    }
}
