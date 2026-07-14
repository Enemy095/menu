package com.salamu.menu.menu.internal.application;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Controlled public 404 when an active tenant has no current published menu. */
public final class PublishedMenuNotFoundException extends ResponseStatusException {

    public PublishedMenuNotFoundException() {
        super(HttpStatus.NOT_FOUND, "Published menu not found");
    }
}
