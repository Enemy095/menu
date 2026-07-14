package com.salamu.menu.restaurant.internal.application;

/** Safe marker for malformed persisted public configuration; common handling returns a generic 500. */
public final class RestaurantConfigurationCorruptException extends RuntimeException {

    public RestaurantConfigurationCorruptException() {
        super("Stored restaurant configuration is invalid");
    }

    public RestaurantConfigurationCorruptException(Throwable cause) {
        super("Stored restaurant configuration is invalid", cause);
    }
}
