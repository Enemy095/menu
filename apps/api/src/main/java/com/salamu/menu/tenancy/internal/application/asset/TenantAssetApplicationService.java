package com.salamu.menu.tenancy.internal.application.asset;

import java.net.URI;
import java.util.Objects;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import com.salamu.menu.tenancy.api.InvalidTenantAssetException;
import com.salamu.menu.tenancy.api.TenantAsset;
import com.salamu.menu.tenancy.api.TenantAssetId;
import com.salamu.menu.tenancy.api.TenantAssetImportRequest;
import com.salamu.menu.tenancy.api.TenantAssetNotFoundException;
import com.salamu.menu.tenancy.api.TenantAssetService;
import com.salamu.menu.tenancy.api.TenantId;

@Service
@Lazy
final class TenantAssetApplicationService implements TenantAssetService {

    private final AssetContentPolicy contentPolicy;
    private final AssetKeyPolicy keyPolicy;
    private final AssetObjectStorage objectStorage;
    private final AssetPersistence persistence;
    private final AssetPublicUrlResolver publicUrlResolver;

    TenantAssetApplicationService(
            AssetContentPolicy contentPolicy,
            AssetKeyPolicy keyPolicy,
            AssetObjectStorage objectStorage,
            AssetPersistence persistence,
            AssetPublicUrlResolver publicUrlResolver) {
        this.contentPolicy = contentPolicy;
        this.keyPolicy = keyPolicy;
        this.objectStorage = objectStorage;
        this.persistence = persistence;
        this.publicUrlResolver = publicUrlResolver;
    }

    @Override
    public TenantAsset importAsset(TenantId tenantId, TenantAssetImportRequest request) {
        Objects.requireNonNull(tenantId, "tenantId");
        ValidatedAssetContent validated = contentPolicy.validate(request);
        AssetObjectKey objectKey = keyPolicy.create(tenantId, validated);

        persistence.requireActiveTenant(tenantId);
        AssetRecord registered = registerOrFind(tenantId, validated, objectKey);
        if (registered.status() == AssetStatus.DELETED) {
            throw new InvalidTenantAssetException("Deleted immutable asset content cannot be re-imported");
        }

        objectStorage.putImmutable(new AssetObject(
                registered.objectKey(),
                registered.contentType(),
                validated.content(),
                registered.sha256()));

        AssetRecord active = registered.status() == AssetStatus.ACTIVE
                ? registered
                : persistence.activate(tenantId, registered.id());
        return toApi(active);
    }

    @Override
    public TenantAsset getAsset(TenantId tenantId, TenantAssetId assetId) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(assetId, "assetId");
        persistence.requireActiveTenant(tenantId);
        return persistence.findActiveById(tenantId, assetId)
                .map(this::toApi)
                .orElseThrow(TenantAssetNotFoundException::new);
    }

    private AssetRecord registerOrFind(
            TenantId tenantId,
            ValidatedAssetContent validated,
            AssetObjectKey objectKey) {
        return persistence.findByContent(tenantId, validated.kind(), validated.sha256())
                .orElseGet(() -> registerPending(tenantId, validated, objectKey));
    }

    private AssetRecord registerPending(
            TenantId tenantId,
            ValidatedAssetContent validated,
            AssetObjectKey objectKey) {
        try {
            return persistence.registerPending(
                    tenantId,
                    validated.kind(),
                    objectKey,
                    validated.contentType(),
                    validated.sizeBytes(),
                    validated.sha256());
        } catch (AssetRegistrationConflictException conflict) {
            return persistence.findByContent(tenantId, validated.kind(), validated.sha256())
                    .orElseThrow(() -> conflict);
        }
    }

    private TenantAsset toApi(AssetRecord record) {
        URI publicUrl = publicUrlResolver.resolve(record.objectKey());
        return new TenantAsset(
                record.id(),
                record.tenantId(),
                record.kind(),
                record.contentType(),
                record.sizeBytes(),
                record.sha256().value(),
                publicUrl);
    }
}
