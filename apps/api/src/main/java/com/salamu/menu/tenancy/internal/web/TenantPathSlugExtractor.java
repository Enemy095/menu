package com.salamu.menu.tenancy.internal.web;

import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.stereotype.Component;

@Component
final class TenantPathSlugExtractor {

    private static final String PUBLIC_API_PREFIX = "/api/v1/public";

    PathTenant pathTenant(HttpServletRequest request) {
        var requestPath = request.getRequestURI();
        var contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && requestPath.startsWith(contextPath)) {
            requestPath = requestPath.substring(contextPath.length());
        }

        if (!requestPath.equals(PUBLIC_API_PREFIX)
                && !requestPath.startsWith(PUBLIC_API_PREFIX + "/")) {
            return new PathTenant(false, Optional.empty());
        }

        var remainder = requestPath.substring(PUBLIC_API_PREFIX.length());
        if (remainder.startsWith("/")) {
            remainder = remainder.substring(1);
        }
        if (remainder.isEmpty()) {
            return new PathTenant(true, Optional.empty());
        }
        var separator = remainder.indexOf('/');
        var slug = separator < 0 ? remainder : remainder.substring(0, separator);
        return new PathTenant(true, Optional.of(slug));
    }

    record PathTenant(boolean tenantBound, Optional<String> slug) {

        PathTenant {
            slug = slug == null ? Optional.empty() : slug;
        }
    }
}
