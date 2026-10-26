package com.salamu.menu.identity.internal.security;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("production")
class ProductionSecurityPropertiesValidator implements InitializingBean {

    private final SecurityProperties properties;

    ProductionSecurityPropertiesValidator(SecurityProperties properties) {
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        if (properties.requestAuthority().allowedHosts().isEmpty()) {
            throw new IllegalStateException("Production request authority allowlist must be explicit");
        }
        if (properties.cors().allowedOrigins().isEmpty()) {
            throw new IllegalStateException("Production CORS origin allowlist must be explicit");
        }
    }
}
