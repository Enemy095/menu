package com.salamu.menu.notification.api;

/** Stable redacted failure. It never includes a destination reference or provider response. */
public final class NotificationProvisioningException extends RuntimeException {

    private final Code code;

    public NotificationProvisioningException(Code code) {
        super(code.message);
        this.code = code;
    }

    public Code code() {
        return code;
    }

    public enum Code {
        TENANT_STATE_CONFLICT("Tenant is not eligible for notification provisioning"),
        DESTINATION_CONFLICT("Notification destination differs from the expected semantic state"),
        CLEANUP_CONFLICT("Notification destination cleanup ownership could not be proven"),
        PERSISTED_STATE_INVALID("Persisted notification destination state is invalid");

        private final String message;

        Code(String message) {
            this.message = message;
        }
    }
}
