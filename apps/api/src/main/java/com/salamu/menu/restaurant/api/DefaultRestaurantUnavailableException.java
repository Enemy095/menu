package com.salamu.menu.restaurant.api;

/** A deliberately redacted signal that the deployment has no unambiguous active default restaurant. */
public final class DefaultRestaurantUnavailableException extends RuntimeException {

    public DefaultRestaurantUnavailableException() {
        super("Default restaurant is unavailable");
    }
}
