package com.salamu.menu.identity.internal.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import com.salamu.menu.TraceIdAccessor;
import tools.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestAuthorityFilterTests {

    private static final String TRACE_ID = "f7ab469535a145e08e9c094f86181f87";

    @Test
    void normalizesDnsIpv4Ipv6TrailingDotCaseAndValidPort() throws Exception {
        var filter = filter(Set.of("menu.example.test", "127.0.0.1", "[::1]"));
        for (var authority : Set.of(
                "MENU.EXAMPLE.TEST.:8443",
                "127.0.0.1:8080",
                "[::1]:8080")) {
            var continued = new AtomicInteger();
            filter.doFilter(request(authority), new MockHttpServletResponse(),
                    (ignoredRequest, ignoredResponse) -> continued.incrementAndGet());
            assertThat(continued).as(authority).hasValue(1);
        }
    }

    @Test
    void allowedAliasesAndForgedForwardedHostReachTheSameDownstreamFlow() throws Exception {
        var filter = filter(Set.of("allowed.example.test", "alias.example.test"));
        for (var authority : Set.of("allowed.example.test", "alias.example.test")) {
            var request = request(authority);
            request.addHeader("Forwarded", "host=attacker.example");
            request.addHeader("X-Forwarded-Host", "attacker.example");
            var marker = new AtomicInteger();

            filter.doFilter(request, new MockHttpServletResponse(),
                    (ignoredRequest, ignoredResponse) -> marker.incrementAndGet());

            assertThat(marker).hasValue(1);
        }
    }

    @Test
    void missingMultipleMalformedUnknownAndOversizedHostFailClosedWithoutEcho() throws Exception {
        var filter = filter(Set.of("allowed.example.test"));

        assertRejected(filter, new MockHttpServletRequest("GET", "/api/v1/public/config"), "missing");

        var multiple = request("allowed.example.test");
        multiple.addHeader("Host", "alias.example.test");
        assertRejected(filter, multiple, "alias.example.test");

        for (var authority : Set.of(
                "unknown.example.test",
                "user@allowed.example.test",
                "allowed.example.test/path",
                "allowed.example.test?query=true",
                "allowed.example.test#fragment",
                "allowed.example.test:0",
                "allowed.example.test:65536",
                "2001:db8::1",
                "010.0.0.1",
                "bad host")) {
            assertRejected(filter, request(authority), authority);
        }

        assertRejected(filter, request("a".repeat(RequestAuthorityFilter.MAX_HOST_HEADER_LENGTH + 1)), "aaaa");
    }

    @Test
    void parserRejectsControlCharactersInvalidIdnAndAmbiguousPorts() {
        assertThat(RequestAuthorityParser.normalizeRequestAuthority("allowed.example.test\r\nX-Evil: yes")).isEmpty();
        assertThat(RequestAuthorityParser.normalizeRequestAuthority("\u200B.example.test")).isEmpty();
        assertThat(RequestAuthorityParser.normalizeRequestAuthority("[::1]:not-a-port")).isEmpty();
        assertThat(RequestAuthorityParser.normalizeRequestAuthority("example.test:")).isEmpty();
        assertThat(RequestAuthorityParser.normalizeHostLiteral("example.test:443")).isEmpty();
    }

    private static void assertRejected(
            RequestAuthorityFilter filter,
            MockHttpServletRequest request,
            String sensitiveValue) throws Exception {
        request.setServletPath("/api/v1/public/config");
        var response = new MockHttpServletResponse();
        var continued = new AtomicInteger();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> continued.incrementAndGet());

        assertThat(continued).hasValue(0);
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getHeader(SecurityErrorWriter.TRACE_ID_HEADER)).isEqualTo(TRACE_ID);
        assertThat(response.getContentAsString())
                .contains("\"code\":\"VALIDATION_ERROR\"")
                .contains("\"message\":\"Invalid request authority\"")
                .contains("\"details\":[]")
                .doesNotContain(sensitiveValue);
    }

    private static RequestAuthorityFilter filter(Set<String> hosts) {
        var traceIdAccessor = mock(TraceIdAccessor.class);
        when(traceIdAccessor.currentTraceId(org.mockito.ArgumentMatchers.any())).thenReturn(TRACE_ID);
        var properties = SecurityTestProperties.defaults();
        properties = new SecurityProperties(
                properties.cors(),
                new SecurityProperties.RequestAuthority(hosts),
                properties.trustedProxies(),
                properties.rateLimit());
        return new RequestAuthorityFilter(
                properties,
                new SecurityErrorWriter(new ObjectMapper(), traceIdAccessor));
    }

    private static MockHttpServletRequest request(String host) {
        var request = new MockHttpServletRequest("GET", "/api/v1/public/config");
        request.setServletPath("/api/v1/public/config");
        request.addHeader("Host", host);
        return request;
    }
}
