package com.salamu.menu.tenancy.internal.web;

import java.util.List;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
class TenancyWebConfiguration implements WebMvcConfigurer {

    private final TenantContextArgumentResolver tenantContextArgumentResolver;

    TenancyWebConfiguration(TenantContextArgumentResolver tenantContextArgumentResolver) {
        this.tenantContextArgumentResolver = tenantContextArgumentResolver;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(tenantContextArgumentResolver);
    }
}
