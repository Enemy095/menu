package com.salamu.menu.tenancy.internal.application;

import java.util.Optional;

import com.salamu.menu.tenancy.internal.domain.TenantRegistration;

public interface TenantRegistry {

    Optional<TenantRegistration> findActiveByHostname(String normalizedHostname);

    Optional<TenantRegistration> findActiveBySlug(String normalizedSlug);
}
