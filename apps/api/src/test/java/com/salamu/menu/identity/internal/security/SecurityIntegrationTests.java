package com.salamu.menu.identity.internal.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import com.salamu.menu.CorrelationIdFilter;
import com.salamu.menu.TraceIdAccessor;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@ActiveProfiles("test")
@SpringBootTest(
        classes = SecurityIntegrationTests.SecurityTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.security.rate-limit.client.tokens-per-period=1000",
                "app.security.rate-limit.client.burst-capacity=1000",
                "app.security.rate-limit.application.tokens-per-period=1000",
                "app.security.rate-limit.application.burst-capacity=1000",
                "management.endpoint.health.group.readiness.include=readinessState",
                "management.tracing.export.enabled=false",
                "management.tracing.export.otlp.enabled=false",
                "management.otlp.metrics.export.enabled=false"
        })
class SecurityIntegrationTests {

    private static final String ALLOWED_ORIGIN = "https://frontend.example.test";
    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

    @LocalServerPort
    private int serverPort;

    @Test
    void exposesOnlyCanonicalPublicPairsAndExplicitProbes() throws Exception {
        assertThat(send("127.0.0.1", "GET", "/api/v1/public/config", Map.of()).statusCode()).isEqualTo(200);
        assertThat(send("127.0.0.1", "GET", "/api/v1/public/menu", Map.of()).statusCode()).isEqualTo(200);
        assertThat(send("127.0.0.1", "POST", "/api/v1/public/orders", Map.of()).statusCode()).isEqualTo(200);
        assertThat(send("127.0.0.1", "GET", "/livez", Map.of()).statusCode()).isEqualTo(200);
        assertThat(send("127.0.0.1", "GET", "/readyz", Map.of()).statusCode()).isEqualTo(200);

        for (var denied : new String[] {
                "POST /api/v1/public/config",
                "GET /api/v1/public/orders",
                "GET /api/v1/public/example/menu",
                "POST /api/public/orders",
                "GET /api/v1/admin/test",
                "GET /tenant-admin/test",
                "GET /platform-admin/test",
                "GET /unlisted"
        }) {
            var parts = denied.split(" ", 2);
            assertThat(send("127.0.0.1", parts[0], parts[1], Map.of()).statusCode())
                    .as(denied)
                    .isEqualTo(403);
        }
        assertThat(send("127.0.0.1", "POST", "/livez", Map.of()).statusCode()).isEqualTo(403);
    }

    @Test
    void managementChainKeepsOnlyConfiguredEndpointsPublicAndCreatesNoSession() throws Exception {
        assertThat(send("127.0.0.1", "GET", "/actuator/health", Map.of()).statusCode()).isEqualTo(200);
        assertThat(send("127.0.0.1", "GET", "/actuator/info", Map.of()).statusCode()).isEqualTo(200);
        assertThat(send("127.0.0.1", "GET", "/actuator/env", Map.of()).statusCode()).isNotEqualTo(200);

        var publicResponse = send("127.0.0.1", "GET", "/api/v1/public/config", Map.of());
        assertThat(publicResponse.headers().allValues("Set-Cookie")).isEmpty();
        assertThat(publicResponse.headers().allValues("WWW-Authenticate")).isEmpty();
    }

    @Test
    void aliasesAndForwardedHostHeadersCannotSelectDifferentDataOrEtag() throws Exception {
        for (var path : new String[] {"/api/v1/public/config", "/api/v1/public/menu"}) {
            var numericAlias = send("127.0.0.1", "GET", path, Map.of());
            var dnsAlias = send("localhost", "GET", path, Map.of(
                    "Forwarded", "host=attacker.example",
                    "X-Forwarded-Host", "attacker.example"));

            assertThat(dnsAlias.statusCode()).isEqualTo(200);
            assertThat(dnsAlias.body()).isEqualTo(numericAlias.body());
            assertThat(dnsAlias.headers().firstValue("ETag"))
                    .isEqualTo(numericAlias.headers().firstValue("ETag"));
        }
    }

    @Test
    void unknownHostReturnsGenericNoStoreEnvelopeBeforeController() throws Exception {
        var response = rawGet("unknown-secret.example", "/api/v1/public/config");

        assertThat(response).startsWith("HTTP/1.1 400");
        assertThat(response.toLowerCase()).contains("cache-control: no-store");
        assertThat(response)
                .contains("\"code\":\"VALIDATION_ERROR\"")
                .contains("\"message\":\"Invalid request authority\"")
                .doesNotContain("unknown-secret.example", "config-marker");
    }

    @Test
    void corsIsExactPerRouteMethodAndHeaderWithoutCredentials() throws Exception {
        var configPreflight = send("127.0.0.1", "OPTIONS", "/api/v1/public/config", Map.of(
                "Origin", ALLOWED_ORIGIN,
                "Access-Control-Request-Method", "GET",
                "Access-Control-Request-Headers", "Accept,X-Correlation-Id"));
        assertThat(configPreflight.statusCode()).isEqualTo(200);
        assertThat(configPreflight.headers().firstValue("Access-Control-Allow-Origin")).contains(ALLOWED_ORIGIN);
        assertThat(configPreflight.headers().firstValue("Access-Control-Allow-Credentials")).isEmpty();

        var orderPreflight = send("127.0.0.1", "OPTIONS", "/api/v1/public/orders", Map.of(
                "Origin", ALLOWED_ORIGIN,
                "Access-Control-Request-Method", "POST",
                "Access-Control-Request-Headers", "Content-Type,Idempotency-Key,traceparent"));
        assertThat(orderPreflight.statusCode()).isEqualTo(200);

        var wrongHeader = send("127.0.0.1", "OPTIONS", "/api/v1/public/config", Map.of(
                "Origin", ALLOWED_ORIGIN,
                "Access-Control-Request-Method", "GET",
                "Access-Control-Request-Headers", "Idempotency-Key"));
        assertThat(wrongHeader.statusCode()).isEqualTo(403);

        var wrongOrigin = send("127.0.0.1", "OPTIONS", "/api/v1/public/config", Map.of(
                "Origin", "https://attacker.example",
                "Access-Control-Request-Method", "GET"));
        assertThat(wrongOrigin.statusCode()).isEqualTo(403);

        var actual = send("127.0.0.1", "GET", "/api/v1/public/config", Map.of("Origin", ALLOWED_ORIGIN));
        assertThat(actual.headers().firstValue("Access-Control-Expose-Headers").orElse(""))
                .contains(
                        "X-Correlation-Id",
                        "X-Trace-Id",
                        "ETag",
                        "Cache-Control",
                        "Cache-Tag",
                        "Surrogate-Key",
                        "Retry-After");
        assertThat(actual.headers().firstValue("Access-Control-Allow-Credentials")).isEmpty();
    }

    @Test
    void csrfIgnoreIsExactToOrderAndErrorDispatchPreservesFailure() throws Exception {
        assertThat(send("127.0.0.1", "POST", "/api/v1/public/orders", Map.of()).statusCode()).isEqualTo(200);
        assertThat(send("127.0.0.1", "POST", "/api/v1/admin/test", Map.of()).statusCode()).isEqualTo(403);
        assertThat(send("127.0.0.1", "GET", "/api/v1/public/menu?fail=true", Map.of()).statusCode())
                .isEqualTo(500);
    }

    private HttpResponse<String> send(
            String authority,
            String method,
            String path,
            Map<String, String> headers) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://" + authority + ":" + serverPort + path));
        headers.forEach(request::header);
        request.method(method, HttpRequest.BodyPublishers.noBody());
        return HTTP_CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String rawGet(String hostHeader, String path) throws Exception {
        try (var socket = new Socket("127.0.0.1", serverPort);
                var writer = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII);
                var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
            writer.write("GET " + path + " HTTP/1.1\r\n");
            writer.write("Host: " + hostHeader + "\r\n");
            writer.write("Connection: close\r\n\r\n");
            writer.flush();
            return reader.lines().collect(java.util.stream.Collectors.joining("\n"));
        }
    }

    @SpringBootApplication(
            scanBasePackages = "com.salamu.menu.identity.internal.security",
            excludeName = {
            "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
            "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
            "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"
            })
    @EnableConfigurationProperties(SecurityProperties.class)
    @Import({TraceIdAccessor.class, CorrelationIdFilter.class, TestApi.class})
    static class SecurityTestApplication {
    }

    @RestController
    static class TestApi {

        @GetMapping("/api/v1/public/config")
        ResponseEntity<String> config() {
            return ResponseEntity.ok().eTag("config-v1").body("config-marker");
        }

        @GetMapping("/api/v1/public/menu")
        ResponseEntity<String> menu(@RequestParam(defaultValue = "false") boolean fail) {
            if (fail) {
                throw new IllegalStateException("test failure without personal data");
            }
            return ResponseEntity.ok().eTag("menu-v1").body("menu-marker");
        }

        @PostMapping("/api/v1/public/orders")
        String order() {
            return "order-marker";
        }

        @PostMapping("/api/v1/admin/test")
        String adminWrite() {
            return "must-not-be-public";
        }

        @GetMapping({"/api/v1/admin/test", "/tenant-admin/test", "/platform-admin/test", "/unlisted"})
        String deniedRead() {
            return "must-not-be-public";
        }
    }
}
