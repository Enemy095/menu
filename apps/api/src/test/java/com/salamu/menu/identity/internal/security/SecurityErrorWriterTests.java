package com.salamu.menu.identity.internal.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Set;

import com.salamu.menu.TraceIdAccessor;
import tools.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class SecurityErrorWriterTests {

    @Test
    void writesTypedFourFieldEnvelopeAndSanitizesDiagnosticFailure() throws Exception {
        var traceIdAccessor = mock(TraceIdAccessor.class);
        var request = new MockHttpServletRequest();
        request.addHeader("Host", "secret.example.test");
        when(traceIdAccessor.currentTraceId(request)).thenThrow(new IllegalStateException("secret-provider-detail"));
        var response = new MockHttpServletResponse();
        var objectMapper = new ObjectMapper();

        new SecurityErrorWriter(objectMapper, traceIdAccessor).write(
                request,
                response,
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR",
                "Invalid request authority",
                null);

        var body = objectMapper.readTree(response.getContentAsByteArray());
        assertThat(body.properties())
                .extracting(java.util.Map.Entry::getKey)
                .containsExactlyInAnyOrderElementsOf(Set.of("code", "message", "details", "traceId"));
        assertThat(body.get("code").asString()).isEqualTo("VALIDATION_ERROR");
        assertThat(body.get("details").isArray()).isTrue();
        assertThat(body.get("details").size()).isZero();
        assertThat(body.get("traceId").asString()).matches("[0-9a-f]{32}");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getContentAsString())
                .doesNotContain("secret.example.test", "secret-provider-detail");
    }
}
