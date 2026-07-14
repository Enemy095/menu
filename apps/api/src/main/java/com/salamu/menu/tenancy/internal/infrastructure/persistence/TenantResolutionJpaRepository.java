package com.salamu.menu.tenancy.internal.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

interface TenantResolutionJpaRepository extends Repository<TenantEntity, UUID> {

    @Query("""
            select new com.salamu.menu.tenancy.internal.infrastructure.persistence.TenantLookupRow(
                tenant.id,
                tenant.slug
            )
            from TenantDomainPersistenceEntity domain
            join TenantPersistenceEntity tenant on tenant.id = domain.tenantId
            where domain.hostname = :hostname
              and domain.status = 'active'
              and domain.verifiedAt is not null
              and tenant.status = 'active'
            """)
    Optional<TenantLookupRow> findActiveByHostname(@Param("hostname") String normalizedHostname);

    @Query("""
            select new com.salamu.menu.tenancy.internal.infrastructure.persistence.TenantLookupRow(
                tenant.id,
                tenant.slug
            )
            from TenantPersistenceEntity tenant
            where tenant.slug = :slug
              and tenant.status = 'active'
            """)
    Optional<TenantLookupRow> findActiveBySlug(@Param("slug") String normalizedSlug);
}
