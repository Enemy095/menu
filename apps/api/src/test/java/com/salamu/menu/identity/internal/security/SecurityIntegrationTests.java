package com.salamu.menu.identity.internal.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import com.salamu.menu.CorrelationIdFilter;
import com.salamu.menu.TraceIdAccessor;
import com.salamu.menu.tenancy.api.TenantContext;
import com.salamu.menu.tenancy.api.TenantId;
import com.salamu.menu.tenancy.api.TenantRequestAccess;
import com.salamu.menu.tenancy.api.TenantResolution;
import com.salamu.menu.tenancy.api.TrustedProxyPolicy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@ActiveProfiles("test")
@SpringBootTest(
        classes = SecurityIntegrationTests.SecurityTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.security.cors.allowed-origins[0]=https://frontend.example.test",
                "management.endpoint.health.group.readiness.include=readinessState",
                "management.tracing.export.enabled=false",
                "management.tracing.export.otlp.enabled=false",
                "management.otlp.metrics.export.enabled=false"
        })
class SecurityIntegrationTests {

    private static final String ALLOWED_ORIGIN = "https://frontend.example.test";

    @LocalServerPort
    private int serverPort;

    @Test
    void exposesOnlyExactPublicMethodAndPathPairs() throws Exception {
        assertThat(send("GET", "/api/v1/public/routes/restaurant", Map.of()).statusCode()).isEqualTo(200);
        assertThat(send("GET", "/api/v1/public/routes/menu", Map.of()).statusCode()).isEqualTo(200);
        assertThat(send("POST", "/api/v1/public/routes/orders", Map.of()).statusCode()).isEqualTo(200);

        assertThat(send("POST", "/api/v1/public/routes/restaurant", Map.of()).statusCode()).isEqualTo(403);
        assertThat(send("GET", "/api/v1/public/routes/orders", Map.of()).statusCode()).isEqualTo(403);
        assertThat(send("GET", "/api/v1/public/routes/unknown", Map.of()).statusCode()).isEqualTo(403);
        assertThat(send("GET", "/api/v1/admin/test", Map.of()).statusCode()).isEqualTo(403);
        assertThat(send("GET", "/api/v1/platform-admin/test", Map.of()).statusCode()).isEqualTo(403);
        assertThat(send("GET", "/unlisted", Map.of()).statusCode()).isEqualTo(403);
    }

    @Test
    void exposesOnlyConfiguredManagementEndpointsAndExplicitProbes() throws Exception {
        assertThat(send("GET", "/livez", Map.of()).statusCode()).isEqualTo(200);
        assertThat(send("GET", "/readyz", Map.of()).statusCode()).isEqualTo(200);
        assertThat(send("GET", "/actuator/health", Map.of()).statusCode()).isEqualTo(200);
        assertThat(send("GET", "/actuator/info", Map.of()).statusCode()).isEqualTo(200);
        assertThat(send("GET", "/actuator/env", Map.of()).statusCode()).isNotEqualTo(200);
        assertThat(send("POST", "/livez", Map.of()).statusCode()).isEqualTo(403);
    }

    @Test
    void permittedPublicFailureKeepsItsErrorStatusAcrossErrorDispatch() throws Exception {
        assertThat(send("GET", "/api/v1/public/failure/menu", Map.of()).statusCode())
                .isEqualTo(500);
    }

    @Test
    void corsIsExactOriginRouteAndMethodAllowlistWithoutCredentials() throws Exception {
        var allowed = send("OPTIONS", "/api/v1/public/cors/restaurant", Map.of(
                "Origin", ALLOWED_ORIGIN,
                "Access-Control-Request-Method", "GET"));
        assertThat(allowed.statusCode()).isEqualTo(200);
        assertThat(allowed.headers().firstValue("Access-Control-Allow-Origin")).contains(ALLOWED_ORIGIN);
        assertThat(allowed.headers().firstValue("Access-Control-Allow-Credentials")).isEmpty();

        var disallowedOrigin = send("OPTIONS", "/api/v1/public/cors/restaurant", Map.of(
                "Origin", "https://attacker.example",
                "Access-Control-Request-Method", "GET"));
        assertThat(disallowedOrigin.statusCode()).isEqualTo(403);

        var disallowedMethod = send("OPTIONS", "/api/v1/public/cors/restaurant", Map.of(
                "Origin", ALLOWED_ORIGIN,
                "Access-Control-Request-Method", "POST"));
        assertThat(disallowedMethod.statusCode()).isEqualTo(403);
    }

    @Test
    void missingTrustedTenantContextReturnsContract404() throws Exception {
        var response = send("POST", "/api/v1/public/missing/orders", Map.of());

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body())
                .contains("\"code\":\"TENANT_NOT_FOUND\"")
                .contains("\"details\":[]")
                .contains("\"traceId\":");
    }

    @Test
    void excessOrderReturnsContract429WithoutLeakingForwardedInput() throws Exception {
        var headers = Map.of("X-Forwarded-For", "phone=+79991112233,address=Secret-street");
        for (var requestNumber = 0; requestNumber < 10; requestNumber++) {
            assertThat(send("POST", "/api/v1/public/limit-test/orders", headers).statusCode())
                    .isEqualTo(200);
        }

        var rejected = send("POST", "/api/v1/public/limit-test/orders", headers);
        assertThat(rejected.statusCode()).isEqualTo(429);
        assertThat(rejected.headers().firstValue("Retry-After").map(Long::parseLong))
                .hasValueSatisfying(value -> assertThat(value).isPositive());
        assertThat(rejected.body())
                .contains("\"code\":\"RATE_LIMITED\"")
                .contains("\"details\":[]")
                .contains("\"traceId\":")
                .doesNotContain("79991112233", "Secret-street", "limit-test", "127.0.0.1");
    }

    private HttpResponse<String> send(String method, String path, Map<String, String> headers) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + path));
        headers.forEach(request::header);
        request.method(method, HttpRequest.BodyPublishers.noBody());
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @SpringBootApplication(
            scanBasePackages = "com.salamu.menu.identity.internal.security",
            excludeName = {
            "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
            "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
            "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"
            })
    @EnableConfigurationProperties(SecurityProperties.class)
    @Import({
            SecurityConfiguration.class,
            TrustedClientIpResolver.class,
            TenantRateLimiter.class,
            TraceIdAccessor.class,
            CorrelationIdFilter.class,
            TestApi.class,
            TestTenancyConfiguration.class
    })
    static class SecurityTestApplication {
    }

    @Configuration(proxyBeanMethods = false)
    static class TestTenancyConfiguration {

        private static final Pattern TENANT_PATH = Pattern.compile("^/api/v1/public/([^/]+)/.*$");

        @Bean
        TrustedProxyPolicy trustedProxyPolicy() {
            return remoteAddress -> false;
        }

        @Bean
        TenantRequestAccess tenantRequestAccess() {
            return request -> {
                var matcher = TENANT_PATH.matcher(request.getRequestURI());
                if (!matcher.matches() || "missing".equals(matcher.group(1))) {
                    return Optional.empty();
                }
                var slug = matcher.group(1);
                var context = new TenantContext(
                        new TenantId(UUID.nameUUIDFromBytes(slug.getBytes(StandardCharsets.UTF_8))),
                        slug);
                return Optional.of(new TenantResolution(
                        context,
                        request.getServerName(),
                        Optional.of(slug),
                        TenantResolution.Source.LOCAL_PATH));
            };
        }
    }

    @RestController
    static class TestApi {

        @GetMapping("/api/v1/public/{tenantSlug}/restaurant")
        String restaurant() {
            return "restaurant";
        }

        @GetMapping("/api/v1/public/{tenantSlug}/menu")
        String menu(@PathVariable String tenantSlug) {
            if ("failure".equals(tenantSlug)) {
                throw new IllegalStateException("test failure without personal data");
            }
            return "menu";
        }

        @PostMapping("/api/v1/public/{tenantSlug}/orders")
        String order() {
            return "order";
        }

        @GetMapping("/api/v1/admin/test")
        String tenantAdmin() {
            return "must-not-be-public";
        }

        @GetMapping("/api/v1/platform-admin/test")
        String platformAdmin() {
            return "must-not-be-public";
        }

        @GetMapping("/unlisted")
        String unlisted() {
            return "must-not-be-public";
        }
    }
}
