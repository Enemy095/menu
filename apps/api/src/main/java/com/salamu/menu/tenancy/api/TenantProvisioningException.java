package com.salamu.menu.tenancy.api;

/** Stable, redacted provisioning failure. No configuration or provenance material is included. */
public final class TenantProvisioningException extends RuntimeException {

    private final Code code;

    public TenantProvisioningException(Code code) {
        super(code.message());
        this.code = code;
    }

    public Code code() {
        return code;
    }

    public enum Code {
        CONCURRENT_CREATE_RETRY_REQUIRED(
                "Concurrent tenant creation requires a new advisory-locked acquisition transaction", true),
        TENANT_STATE_CONFLICT("Tenant provisioning state conflicts with the requested operation", false),
        CONFIGURATION_CONFLICT("Tenant configuration differs from the expected semantic snapshot", false),
        CONFIGURATION_INCOMPLETE("Tenant configuration is incomplete", false),
        BATCH_CONFLICT("Tenant activation batch cannot be applied atomically", false),
        CLEANUP_CONFLICT("Tenant cleanup ownership or references could not be proven", false),
        PERSISTED_STATE_INVALID("Persisted tenant provisioning state is invalid", false);

        private final String message;
        private final boolean retryableAfterRollback;

        Code(String message, boolean retryableAfterRollback) {
            this.message = message;
            this.retryableAfterRollback = retryableAfterRollback;
        }

        String message() {
            return message;
        }

        /** Retry is allowed only after the failed transaction rolls back and a new advisory lock is acquired. */
        public boolean retryableAfterRollback() {
            return retryableAfterRollback;
        }
    }
}
