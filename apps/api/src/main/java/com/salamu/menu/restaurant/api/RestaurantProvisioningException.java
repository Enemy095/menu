package com.salamu.menu.restaurant.api;

/** Stable and redacted failure from the temporary restaurant provisioning boundary. */
public final class RestaurantProvisioningException extends RuntimeException {

    private final Code code;

    public RestaurantProvisioningException(Code code) {
        super(code.message);
        this.code = code;
    }

    public Code code() {
        return code;
    }

    public enum Code {
        TENANT_STATE_CONFLICT("Tenant is not eligible for restaurant provisioning"),
        RESTAURANT_CONFLICT("Default restaurant differs from the expected semantic state"),
        CLEANUP_CONFLICT("Default restaurant cleanup ownership could not be proven"),
        PERSISTED_STATE_INVALID("Persisted default restaurant state is invalid");

        private final String message;

        Code(String message) {
            this.message = message;
        }
    }
}
