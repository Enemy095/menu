package com.salamu.menu.tenancy.internal.application.asset;

/** Storage metadata does not prove ownership by the expected immutable provisioning run. */
public final class AssetObjectProvenanceConflictException extends RuntimeException {

    public AssetObjectProvenanceConflictException() {
        super("Immutable asset object provenance conflict");
    }
}
