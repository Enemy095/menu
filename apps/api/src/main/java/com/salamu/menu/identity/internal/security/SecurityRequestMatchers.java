package com.salamu.menu.identity.internal.security;

import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

final class SecurityRequestMatchers {

    static final String PUBLIC_RESTAURANT_PATH = "/api/v1/public/{tenantSlug}/restaurant";
    static final String PUBLIC_MENU_PATH = "/api/v1/public/{tenantSlug}/menu";
    static final String PUBLIC_ORDER_PATH = "/api/v1/public/{tenantSlug}/orders";

    static final RequestMatcher PUBLIC_RESTAURANT =
            PathPatternRequestMatcher.pathPattern(HttpMethod.GET, PUBLIC_RESTAURANT_PATH);
    static final RequestMatcher PUBLIC_MENU =
            PathPatternRequestMatcher.pathPattern(HttpMethod.GET, PUBLIC_MENU_PATH);
    static final RequestMatcher PUBLIC_ORDER =
            PathPatternRequestMatcher.pathPattern(HttpMethod.POST, PUBLIC_ORDER_PATH);
    static final RequestMatcher LIVENESS =
            PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/livez");
    static final RequestMatcher READINESS =
            PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/readyz");

    private SecurityRequestMatchers() {
    }
}
