package com.salamu.menu.menu.api;

import java.util.Objects;

/** Stable, redacted conflict raised by the temporary menu provisioning boundary. */
public final class MenuProvisioningConflictException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final Code code;

    public MenuProvisioningConflictException(Code code) {
        super(Objects.requireNonNull(code, "code").name());
        this.code = code;
    }

    public Code code() {
        return code;
    }

    public enum Code {
        TENANT_UNAVAILABLE,
        TENANT_LIFECYCLE_CONFLICT,
        PERSISTED_STATE_INVALID,
        SNAPSHOT_CONFLICT,
        FOREIGN_PROVENANCE,
        PUBLISHED_VERSION_CONFLICT,
        STORAGE_CONFLICT,
        CLEANUP_FORBIDDEN
    }
}
