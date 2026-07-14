package com.salamu.menu.tenancy.internal.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;

import com.salamu.menu.tenancy.api.TenantContext;
import com.salamu.menu.tenancy.api.TenantId;
import com.salamu.menu.tenancy.api.TenantResolution;

import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;

class TenantContextArgumentResolverTests {

    @Test
    void resolvesContextAndResolutionFromTheTrustedRequestAttribute() throws Exception {
        var context = new TenantContext(
                new TenantId(UUID.fromString("10000000-0000-4000-8000-000000000001")),
                "stambul");
        var resolution = new TenantResolution(
                context,
                "example.test",
                Optional.of("stambul"),
                TenantResolution.Source.PLATFORM_PATH);
        var request = new MockHttpServletRequest();
        request.setAttribute(TenantRequestAttributeAccess.RESOLUTION_ATTRIBUTE, resolution);
        var resolver = new TenantContextArgumentResolver(new TenantRequestAttributeAccess());
        var webRequest = new ServletWebRequest(request);

        assertThat(resolver.resolveArgument(parameter(0), null, webRequest, null)).isEqualTo(context);
        assertThat(resolver.resolveArgument(parameter(1), null, webRequest, null)).isEqualTo(resolution);
    }

    private static MethodParameter parameter(int index) throws NoSuchMethodException {
        Method method = TenantContextArgumentResolverTests.class.getDeclaredMethod(
                "controllerMethod",
                TenantContext.class,
                TenantResolution.class);
        return new MethodParameter(method, index);
    }

    @SuppressWarnings("unused")
    private void controllerMethod(TenantContext context, TenantResolution resolution) {
    }
}
