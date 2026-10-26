package com.salamu.menu.menu.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Safe public signal used by the root error handler to emit MENU_NOT_FOUND. */
public final class PublishedMenuNotFoundException extends ResponseStatusException {

    public static final String ERROR_CODE = "MENU_NOT_FOUND";

    public PublishedMenuNotFoundException() {
        super(HttpStatus.NOT_FOUND, "Published menu is unavailable");
    }
}
