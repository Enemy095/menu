package com.salamu.menu.identity.internal.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class SecurityRequestMatchersTests {

    @Test
    void permitsOnlyContractedMethodAndPathPairs() {
        assertThat(SecurityRequestMatchers.PUBLIC_RESTAURANT.matches(
                request("GET", "/api/v1/public/stambul/restaurant"))).isTrue();
        assertThat(SecurityRequestMatchers.PUBLIC_MENU.matches(
                request("GET", "/api/v1/public/adamas/menu"))).isTrue();
        assertThat(SecurityRequestMatchers.PUBLIC_ORDER.matches(
                request("POST", "/api/v1/public/adamas/orders"))).isTrue();

        assertThat(SecurityRequestMatchers.PUBLIC_RESTAURANT.matches(
                request("POST", "/api/v1/public/stambul/restaurant"))).isFalse();
        assertThat(SecurityRequestMatchers.PUBLIC_MENU.matches(
                request("GET", "/api/v1/public/adamas/menu/extra"))).isFalse();
        assertThat(SecurityRequestMatchers.PUBLIC_ORDER.matches(
                request("GET", "/api/v1/public/adamas/orders"))).isFalse();
        assertThat(SecurityRequestMatchers.PUBLIC_ORDER.matches(
                request("POST", "/api/v1/public/group/adamas/orders"))).isFalse();
        assertThat(SecurityRequestMatchers.PUBLIC_ORDER.matches(
                request("POST", "/api/v1/public/adamas/orders/"))).isFalse();
        assertThat(SecurityRequestMatchers.PUBLIC_ORDER.matches(
                request("POST", "/api/v1/public/adamas/orders/extra"))).isFalse();
    }

    @Test
    void probeMatchersAreGetOnlyAndExact() {
        assertThat(SecurityRequestMatchers.LIVENESS.matches(request("GET", "/livez"))).isTrue();
        assertThat(SecurityRequestMatchers.READINESS.matches(request("GET", "/readyz"))).isTrue();
        assertThat(SecurityRequestMatchers.LIVENESS.matches(request("POST", "/livez"))).isFalse();
        assertThat(SecurityRequestMatchers.READINESS.matches(request("GET", "/readyz/extra"))).isFalse();
    }

    private static MockHttpServletRequest request(String method, String path) {
        var request = new MockHttpServletRequest(method, path);
        request.setServletPath(path);
        return request;
    }
}
