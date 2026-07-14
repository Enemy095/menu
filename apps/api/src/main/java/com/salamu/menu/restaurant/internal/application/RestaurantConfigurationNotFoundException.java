package com.salamu.menu.restaurant.internal.application;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Deliberately indistinguishable from an unknown tenant at the public boundary. */
public final class RestaurantConfigurationNotFoundException extends ResponseStatusException {

    public RestaurantConfigurationNotFoundException() {
        super(HttpStatus.NOT_FOUND, "Restaurant configuration not found");
    }

    public RestaurantConfigurationNotFoundException(Throwable cause) {
        super(HttpStatus.NOT_FOUND, "Restaurant configuration not found", cause);
    }
}
