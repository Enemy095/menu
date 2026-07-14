package com.salamu.menu.tenancy.internal.infrastructure.persistence;

import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.salamu.menu.tenancy.api.AssetKind;
import com.salamu.menu.tenancy.api.TenantAssetId;
import com.salamu.menu.tenancy.api.TenantAssetProvisioningConflictException;
import com.salamu.menu.tenancy.api.TenantId;
import com.salamu.menu.tenancy.api.TenantLifecycle;
import com.salamu.menu.tenancy.internal.application.asset.AssetObjectKey;
import com.salamu.menu.tenancy.internal.application.asset.AssetProvisioningDescriptor;
import com.salamu.menu.tenancy.internal.application.asset.AssetProvisioningPersistence;
import com.salamu.menu.tenancy.internal.application.asset.AssetRecord;
import com.salamu.menu.tenancy.internal.application.asset.AssetRegistrationConflictException;
import com.salamu.menu.tenancy.internal.application.asset.AssetSha256;
import com.salamu.menu.tenancy.internal.application.asset.AssetStatus;

@Repository
@Lazy
@org.springframework.context.annotation.Profile("legacy-import")
class TenantAssetProvisioningPersistenceAdapter implements AssetProvisioningPersistence {

    private final EntityManager entityManager;

    TenantAssetProvisioningPersistenceAdapter(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<AssetRecord> inspect(
            TenantId tenantId,
            AssetKind kind,
            AssetSha256 sha256,
            TenantLifecycle expectedLifecycle) {
        requireExactLifecycle(tenantId, expectedLifecycle);
        return findByContent(tenantId, kind, sha256).map(TenantAssetProvisioningPersistenceAdapter::toRecord);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AssetRecord registerUploaded(
            TenantId tenantId,
            AssetKind kind,
            AssetObjectKey objectKey,
            String contentType,
            long sizeBytes,
            AssetSha256 sha256,
            TenantLifecycle expectedLifecycle) {
        requireExactLifecycle(tenantId, expectedLifecycle);
        Optional<TenantAssetEntity> existing = findByContent(tenantId, kind, sha256);
        if (existing.isPresent()) {
            TenantAssetEntity entity = existing.orElseThrow();
            AssetRecord record = toRecord(entity);
            requireExactMetadata(record, objectKey, contentType, sizeBytes, sha256);
            if (record.status() == AssetStatus.DELETED) {
                throw conflict("Deleted asset content cannot be provisioned");
            }
            if (record.status() != AssetStatus.ACTIVE) {
                entity.status = AssetStatus.ACTIVE.databaseValue();
                entityManager.flush();
                return toRecord(entity);
            }
            return record;
        }

        TenantAssetEntity entity = new TenantAssetEntity();
        entity.id = UUID.randomUUID();
        entity.tenantId = tenantId.value();
        entity.objectKey = objectKey.value();
        entity.assetType = kind.storageName();
        entity.contentType = contentType;
        entity.sizeBytes = sizeBytes;
        entity.sha256 = sha256.value();
        entity.status = AssetStatus.ACTIVE.databaseValue();
        entity.createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        try {
            entityManager.persist(entity);
            entityManager.flush();
        } catch (RuntimeException exception) {
            if (isUniqueViolation(exception)) {
                throw new AssetRegistrationConflictException(exception);
            }
            throw exception;
        }
        return toRecord(entity);
    }

    @Override
    @Transactional(readOnly = true, propagation = Propagation.MANDATORY)
    public Map<TenantAssetId, AssetRecord> inspectPreparedAssetReferences(
            TenantId tenantId,
            Map<TenantAssetId, AssetKind> expectedAssets,
            TenantLifecycle expectedLifecycle) {
        requireExactLifecycleForInspection(tenantId, expectedLifecycle);
        if (expectedAssets.isEmpty()) {
            return Map.of();
        }
        List<TenantAssetEntity> registered = findPreparedAssets(tenantId, expectedAssets.keySet(), false);
        return requireExactPreparedAssets(tenantId, expectedAssets, registered);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void validatePreparedAssetReferences(
            TenantId tenantId,
            Map<TenantAssetId, AssetKind> expectedAssets,
            TenantLifecycle expectedLifecycle) {
        if (expectedLifecycle != TenantLifecycle.SUSPENDED) {
            throw new IllegalArgumentException("Final asset reference validation requires a suspended tenant");
        }
        requireExactLifecycle(tenantId, expectedLifecycle);
        if (expectedAssets.isEmpty()) {
            return;
        }
        List<TenantAssetEntity> registered = findPreparedAssets(tenantId, expectedAssets.keySet(), true);
        requireExactPreparedAssets(tenantId, expectedAssets, registered);
    }

    private Map<TenantAssetId, AssetRecord> requireExactPreparedAssets(
            TenantId tenantId,
            Map<TenantAssetId, AssetKind> expectedAssets,
            List<TenantAssetEntity> registered) {
        if (registered.size() != expectedAssets.size()) {
            throw conflict("Prepared asset reference set is incomplete");
        }
        Map<TenantAssetId, AssetRecord> result = new LinkedHashMap<>();
        try {
            for (TenantAssetEntity entity : registered) {
                AssetRecord asset = toRecord(entity);
                AssetKind expectedKind = expectedAssets.get(asset.id());
                if (!tenantId.equals(asset.tenantId())
                        || expectedKind == null
                        || expectedKind != asset.kind()
                        || asset.status() != AssetStatus.ACTIVE) {
                    throw conflict("Prepared asset reference does not match tenant metadata");
                }
                result.put(asset.id(), asset);
            }
        } catch (TenantAssetProvisioningConflictException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new TenantAssetProvisioningConflictException(
                    "Prepared asset registry contains invalid metadata", exception);
        }
        return Collections.unmodifiableMap(result);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean removeUnreferencedForCleanup(
            AssetProvisioningDescriptor expected,
            TenantLifecycle expectedLifecycle) {
        requireExactLifecycle(expected.tenantId(), expectedLifecycle);
        Optional<TenantAssetEntity> registered =
                findByContent(expected.tenantId(), expected.kind(), expected.object().sha256());
        if (registered.isEmpty()) {
            return false;
        }
        TenantAssetEntity entity = registered.orElseThrow();
        AssetRecord actual = toRecord(entity);
        requireExactMetadata(
                actual,
                expected.object().key(),
                expected.object().contentType(),
                expected.object().sizeBytes(),
                expected.object().sha256());
        requireUnreferenced(actual);
        try {
            entityManager.remove(entity);
            entityManager.flush();
        } catch (RuntimeException exception) {
            if (isIntegrityViolation(exception)) {
                throw conflict("Referenced asset cannot be cleaned up");
            }
            throw exception;
        }
        return true;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void requireObjectDeletionAllowed(AssetProvisioningDescriptor expected) {
        Optional<TenantEntity> tenant = lockTenant(expected.tenantId());
        if (tenant.isPresent() && !TenantLifecycle.SUSPENDED.databaseValue().equals(tenant.orElseThrow().status)) {
            throw conflict("Tenant lifecycle does not allow asset cleanup");
        }
        if (hasAnyRegistrationForObjectKey(expected.object().key())) {
            throw conflict("Asset registration still exists");
        }
    }

    private void requireExactLifecycle(TenantId tenantId, TenantLifecycle expectedLifecycle) {
        if (expectedLifecycle == TenantLifecycle.DELETED) {
            throw new IllegalArgumentException("Deleted lifecycle cannot be used for asset provisioning");
        }
        TenantEntity tenant = lockTenant(tenantId)
                .orElseThrow(() -> conflict("Tenant lifecycle does not allow asset provisioning"));
        if (!expectedLifecycle.databaseValue().equals(tenant.status)) {
            throw conflict("Tenant lifecycle does not allow asset provisioning");
        }
    }

    private void requireExactLifecycleForInspection(
            TenantId tenantId,
            TenantLifecycle expectedLifecycle) {
        if (expectedLifecycle != TenantLifecycle.ACTIVE && expectedLifecycle != TenantLifecycle.SUSPENDED) {
            throw new IllegalArgumentException("Prepared asset inspection requires an active or suspended tenant");
        }
        TenantEntity tenant = findTenant(tenantId)
                .orElseThrow(() -> conflict("Tenant lifecycle does not allow asset inspection"));
        if (!expectedLifecycle.databaseValue().equals(tenant.status)) {
            throw conflict("Tenant lifecycle does not allow asset inspection");
        }
    }

    private Optional<TenantEntity> lockTenant(TenantId tenantId) {
        List<TenantEntity> tenants = entityManager.createQuery(
                        "select tenant from TenantPersistenceEntity tenant where tenant.id = :tenantId",
                        TenantEntity.class)
                .setParameter("tenantId", tenantId.value())
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .setMaxResults(1)
                .getResultList();
        return tenants.stream().findFirst();
    }

    private Optional<TenantEntity> findTenant(TenantId tenantId) {
        return entityManager.createQuery(
                        "select tenant from TenantPersistenceEntity tenant where tenant.id = :tenantId",
                        TenantEntity.class)
                .setParameter("tenantId", tenantId.value())
                .setMaxResults(1)
                .getResultList()
                .stream()
                .findFirst();
    }

    private List<TenantAssetEntity> findPreparedAssets(
            TenantId tenantId,
            Set<TenantAssetId> assetIds,
            boolean lockForWrite) {
        Set<UUID> expectedIds = assetIds.stream()
                .map(TenantAssetId::value)
                .collect(Collectors.toUnmodifiableSet());
        var query = entityManager.createQuery(
                        """
                        select asset
                        from TenantAssetPersistenceEntity asset
                        where asset.tenantId = :tenantId
                          and asset.id in :assetIds
                        order by asset.id
                        """,
                        TenantAssetEntity.class)
                .setParameter("tenantId", tenantId.value())
                .setParameter("assetIds", expectedIds);
        if (lockForWrite) {
            query.setLockMode(LockModeType.PESSIMISTIC_WRITE);
        }
        return query.getResultList();
    }

    private Optional<TenantAssetEntity> findByContent(
            TenantId tenantId,
            AssetKind kind,
            AssetSha256 sha256) {
        return entityManager.createQuery(
                        """
                        select asset
                        from TenantAssetPersistenceEntity asset
                        where asset.tenantId = :tenantId
                          and asset.assetType = :assetType
                          and asset.sha256 = :sha256
                        """,
                        TenantAssetEntity.class)
                .setParameter("tenantId", tenantId.value())
                .setParameter("assetType", kind.storageName())
                .setParameter("sha256", sha256.value())
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .setMaxResults(1)
                .getResultList()
                .stream()
                .findFirst();
    }

    private void requireUnreferenced(AssetRecord asset) {
        Number referenceCount = (Number) entityManager.createNativeQuery(
                        """
                        SELECT
                            (SELECT count(*) FROM tenant_branding
                             WHERE tenant_id = :tenantId
                               AND (logo_asset_id = :assetId
                                 OR favicon_asset_id = :assetId
                                 OR hero ->> 'imageAssetId' = :assetIdText
                                 OR seo_metadata ->> 'openGraphAssetId' = :assetIdText))
                        """)
                .setParameter("tenantId", asset.tenantId().value())
                .setParameter("assetId", asset.id().value())
                .setParameter("assetIdText", asset.id().value().toString())
                .getSingleResult();
        if (referenceCount.longValue() != 0) {
            throw conflict("Referenced asset cannot be cleaned up");
        }
    }

    private boolean hasAnyRegistrationForObjectKey(AssetObjectKey objectKey) {
        Long count = entityManager.createQuery(
                        """
                        select count(asset)
                        from TenantAssetPersistenceEntity asset
                        where asset.objectKey = :objectKey
                        """,
                        Long.class)
                .setParameter("objectKey", objectKey.value())
                .getSingleResult();
        return count != 0;
    }

    private static void requireExactMetadata(
            AssetRecord actual,
            AssetObjectKey objectKey,
            String contentType,
            long sizeBytes,
            AssetSha256 sha256) {
        if (!actual.objectKey().equals(objectKey)
                || !actual.contentType().equals(contentType)
                || actual.sizeBytes() != sizeBytes
                || !actual.sha256().equals(sha256)) {
            throw conflict("Asset registry metadata conflict");
        }
    }

    private static AssetRecord toRecord(TenantAssetEntity entity) {
        return new AssetRecord(
                new TenantAssetId(entity.id),
                new TenantId(entity.tenantId),
                assetKind(entity.assetType),
                new AssetObjectKey(entity.objectKey),
                entity.contentType,
                entity.sizeBytes,
                new AssetSha256(entity.sha256),
                AssetStatus.fromDatabase(entity.status));
    }

    private static AssetKind assetKind(String value) {
        for (AssetKind kind : AssetKind.values()) {
            if (kind.storageName().equals(value)) {
                return kind;
            }
        }
        throw new IllegalStateException("Unknown tenant asset type");
    }

    private static TenantAssetProvisioningConflictException conflict(String message) {
        return new TenantAssetProvisioningConflictException(message);
    }

    private static boolean isUniqueViolation(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SQLException sqlException && "23505".equals(sqlException.getSQLState())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean isIntegrityViolation(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SQLException sqlException) {
                String sqlState = sqlException.getSQLState();
                if (sqlState != null && sqlState.startsWith("23")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }
}
