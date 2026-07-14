package com.salamu.menu.tenancy.internal.web;

import jakarta.servlet.http.HttpServletRequest;

import com.salamu.menu.tenancy.api.TenantContext;
import com.salamu.menu.tenancy.api.TenantRequestAccess;
import com.salamu.menu.tenancy.api.TenantResolution;
import com.salamu.menu.tenancy.internal.application.TenantNotFoundException;

import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

@Component
final class TenantContextArgumentResolver implements HandlerMethodArgumentResolver {

    private final TenantRequestAccess tenantRequestAccess;

    TenantContextArgumentResolver(TenantRequestAccess tenantRequestAccess) {
        this.tenantRequestAccess = tenantRequestAccess;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType() == TenantContext.class
                || parameter.getParameterType() == TenantResolution.class;
    }

    @Override
    public Object resolveArgument(
            MethodParameter parameter,
            ModelAndViewContainer modelAndViewContainer,
            NativeWebRequest webRequest,
            WebDataBinderFactory binderFactory) {
        var request = webRequest.getNativeRequest(HttpServletRequest.class);
        if (request == null) {
            throw new TenantNotFoundException();
        }
        var resolution = tenantRequestAccess.resolution(request)
                .orElseThrow(TenantNotFoundException::new);
        return parameter.getParameterType() == TenantContext.class
                ? resolution.context()
                : resolution;
    }
}
