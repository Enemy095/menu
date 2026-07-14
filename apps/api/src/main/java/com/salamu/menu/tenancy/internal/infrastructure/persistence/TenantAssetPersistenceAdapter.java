package com.salamu.menu.tenancy.internal.infrastructure.persistence;

import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.salamu.menu.tenancy.api.AssetKind;
import com.salamu.menu.tenancy.api.TenantAssetId;
import com.salamu.menu.tenancy.api.TenantAssetNotFoundException;
import com.salamu.menu.tenancy.api.TenantId;
import com.salamu.menu.tenancy.internal.application.asset.AssetObjectKey;
import com.salamu.menu.tenancy.internal.application.asset.AssetPersistence;
import com.salamu.menu.tenancy.internal.application.asset.AssetRecord;
import com.salamu.menu.tenancy.internal.application.asset.AssetRegistrationConflictException;
import com.salamu.menu.tenancy.internal.application.asset.AssetSha256;
import com.salamu.menu.tenancy.internal.application.asset.AssetStatus;

@Repository
@Lazy
class TenantAssetPersistenceAdapter implements AssetPersistence {

    private static final String ACTIVE_TENANT = "active";

    private final TenantJpaRepository tenantRepository;
    private final TenantAssetJpaRepository assetRepository;
    private final EntityManager entityManager;

    TenantAssetPersistenceAdapter(
            TenantJpaRepository tenantRepository,
            TenantAssetJpaRepository assetRepository,
            EntityManager entityManager) {
        this.tenantRepository = tenantRepository;
        this.assetRepository = assetRepository;
        this.entityManager = entityManager;
    }

    @Override
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public void requireActiveTenant(TenantId tenantId) {
        requireActiveTenantInCurrentTransaction(tenantId);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AssetRecord registerPending(
            TenantId tenantId,
            AssetKind kind,
            AssetObjectKey objectKey,
            String contentType,
            long sizeBytes,
            AssetSha256 sha256) {
        requireActiveTenantInCurrentTransaction(tenantId);
        Optional<AssetRecord> existing = findByContentInCurrentTransaction(tenantId, kind, sha256);
        if (existing.isPresent()) {
            return existing.orElseThrow();
        }

        TenantAssetEntity entity = new TenantAssetEntity();
        entity.id = UUID.randomUUID();
        entity.tenantId = tenantId.value();
        entity.objectKey = objectKey.value();
        entity.assetType = kind.storageName();
        entity.contentType = contentType;
        entity.sizeBytes = sizeBytes;
        entity.sha256 = sha256.value();
        entity.status = AssetStatus.PENDING.databaseValue();
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
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public Optional<AssetRecord> findByContent(TenantId tenantId, AssetKind kind, AssetSha256 sha256) {
        return findByContentInCurrentTransaction(tenantId, kind, sha256);
    }

    @Override
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public Optional<AssetRecord> findActiveById(TenantId tenantId, TenantAssetId assetId) {
        return assetRepository.findByTenantIdAndId(tenantId.value(), assetId.value())
                .filter(entity -> AssetStatus.ACTIVE.databaseValue().equals(entity.status))
                .map(TenantAssetPersistenceAdapter::toRecord);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AssetRecord activate(TenantId tenantId, TenantAssetId assetId) {
        requireActiveTenantInCurrentTransaction(tenantId);
        TenantAssetEntity entity = assetRepository.findByTenantIdAndId(tenantId.value(), assetId.value())
                .orElseThrow(TenantAssetNotFoundException::new);
        AssetStatus status = AssetStatus.fromDatabase(entity.status);
        if (status == AssetStatus.DELETED) {
            throw new TenantAssetNotFoundException();
        }
        entity.status = AssetStatus.ACTIVE.databaseValue();
        entityManager.flush();
        return toRecord(entity);
    }

    private void requireActiveTenantInCurrentTransaction(TenantId tenantId) {
        if (tenantRepository.findByIdAndStatus(tenantId.value(), ACTIVE_TENANT).isEmpty()) {
            throw new TenantAssetNotFoundException();
        }
    }

    private Optional<AssetRecord> findByContentInCurrentTransaction(
            TenantId tenantId,
            AssetKind kind,
            AssetSha256 sha256) {
        List<TenantAssetEntity> matches = entityManager.createQuery(
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
                .setMaxResults(1)
                .getResultList();
        return matches.stream().findFirst().map(TenantAssetPersistenceAdapter::toRecord);
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
        throw new IllegalStateException("Unknown tenant asset type: " + value);
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
}
