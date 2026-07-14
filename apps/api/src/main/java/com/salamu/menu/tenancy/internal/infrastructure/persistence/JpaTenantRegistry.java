package com.salamu.menu.tenancy.internal.infrastructure.persistence;

import java.util.Optional;

import com.salamu.menu.tenancy.api.TenantId;
import com.salamu.menu.tenancy.internal.application.TenantRegistry;
import com.salamu.menu.tenancy.internal.domain.TenantRegistration;

import org.springframework.stereotype.Repository;

@Repository
class JpaTenantRegistry implements TenantRegistry {

    private final TenantResolutionJpaRepository repository;

    JpaTenantRegistry(TenantResolutionJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<TenantRegistration> findActiveByHostname(String normalizedHostname) {
        return repository.findActiveByHostname(normalizedHostname).map(JpaTenantRegistry::registration);
    }

    @Override
    public Optional<TenantRegistration> findActiveBySlug(String normalizedSlug) {
        return repository.findActiveBySlug(normalizedSlug).map(JpaTenantRegistry::registration);
    }

    private static TenantRegistration registration(TenantLookupRow row) {
        return new TenantRegistration(new TenantId(row.tenantId()), row.slug());
    }
}
