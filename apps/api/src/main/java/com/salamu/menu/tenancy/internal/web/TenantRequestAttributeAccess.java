package com.salamu.menu.tenancy.internal.web;

import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;

import com.salamu.menu.tenancy.api.TenantRequestAccess;
import com.salamu.menu.tenancy.api.TenantResolution;

import org.springframework.stereotype.Component;

@Component
final class TenantRequestAttributeAccess implements TenantRequestAccess {

    static final String RESOLUTION_ATTRIBUTE = TenantRequestAttributeAccess.class.getName() + ".resolution";

    @Override
    public Optional<TenantResolution> resolution(HttpServletRequest request) {
        var value = request.getAttribute(RESOLUTION_ATTRIBUTE);
        return value instanceof TenantResolution resolution ? Optional.of(resolution) : Optional.empty();
    }
}
