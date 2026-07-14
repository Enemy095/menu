package com.salamu.menu.tenancy.internal.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.repository.Repository;

interface TenantJpaRepository extends Repository<TenantEntity, UUID> {

    Optional<TenantEntity> findByIdAndStatus(UUID tenantId, String status);
}

interface TenantDomainJpaRepository extends Repository<TenantDomainEntity, UUID> {

    Optional<TenantDomainEntity> findByTenantIdAndId(UUID tenantId, UUID id);

    List<TenantDomainEntity> findAllByTenantIdAndStatus(UUID tenantId, String status);
}

interface TenantBrandingJpaRepository extends Repository<TenantBrandingEntity, UUID> {

    Optional<TenantBrandingEntity> findByTenantId(UUID tenantId);
}

interface TenantProfileJpaRepository extends Repository<TenantProfileEntity, UUID> {

    Optional<TenantProfileEntity> findByTenantId(UUID tenantId);
}

interface TenantFeatureJpaRepository extends Repository<TenantFeatureEntity, TenantFeatureId> {

    List<TenantFeatureEntity> findAllByIdTenantId(UUID tenantId);
}

interface TenantAssetJpaRepository extends Repository<TenantAssetEntity, UUID> {

    Optional<TenantAssetEntity> findByTenantIdAndId(UUID tenantId, UUID id);
}
