package com.salamu.menu;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorrelationIdFilterTests {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void propagatesSafeClientCorrelationIdAndCleansMdc() throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader(
                CorrelationIdFilter.CORRELATION_ID_HEADER,
                "5D8505CB-35A6-42E2-8B02-DD6737003BE1");
        var response = new MockHttpServletResponse();
        var valueInsideChain = new AtomicReference<String>();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) ->
                valueInsideChain.set(MDC.get(CorrelationIdFilter.MDC_KEY)));

        assertThat(response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER))
                .isEqualTo("5d8505cb35a642e28b02dd6737003be1");
        assertThat(request.getAttribute(CorrelationIdFilter.CORRELATION_ID_ATTRIBUTE))
                .isEqualTo("5d8505cb35a642e28b02dd6737003be1");
        assertThat(valueInsideChain).hasValue("5d8505cb35a642e28b02dd6737003be1");
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void rejectsUnsafeCorrelationIdAndRestoresPreviousMdcValue() throws Exception {
        MDC.put(CorrelationIdFilter.MDC_KEY, "outer-request");
        var request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, "phone=+79991112233\ninvalid");
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> {
            var generated = MDC.get(CorrelationIdFilter.MDC_KEY);
            assertThat(generated).matches("[a-f0-9]{32}");
            assertThat(generated).doesNotContain("79991112233");
        });

        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isEqualTo("outer-request");
    }

    @Test
    void acceptsCompactHexCorrelationId() {
        assertThat(CorrelationIdFilter.resolveCorrelationId("F7AB469535A145E08E9C094F86181F87"))
                .isEqualTo("f7ab469535a145e08e9c094f86181f87");
    }
}
