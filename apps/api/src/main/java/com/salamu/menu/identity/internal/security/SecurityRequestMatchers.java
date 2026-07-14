package com.salamu.menu.identity.internal.security;

import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

final class SecurityRequestMatchers {

    static final String PUBLIC_CONFIG_PATH = "/api/v1/public/config";
    static final String PUBLIC_MENU_PATH = "/api/v1/public/menu";
    static final String PUBLIC_ORDER_PATH = "/api/v1/public/orders";

    static final RequestMatcher PUBLIC_CONFIG =
            PathPatternRequestMatcher.pathPattern(HttpMethod.GET, PUBLIC_CONFIG_PATH);
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
