package com.salamu.menu.tenancy.internal.application;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class TenantNotFoundException extends ResponseStatusException {

    public TenantNotFoundException() {
        super(HttpStatus.NOT_FOUND, "Tenant not found");
    }
}
