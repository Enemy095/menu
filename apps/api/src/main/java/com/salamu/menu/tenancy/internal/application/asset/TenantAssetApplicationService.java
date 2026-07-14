package com.salamu.menu.tenancy.internal.application.asset;

import java.net.URI;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

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

    private static final int MAX_BATCH_SIZE = 500;

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

    @Override
    public Map<TenantAssetId, TenantAsset> getActiveAssets(
            TenantId tenantId,
            Collection<TenantAssetId> assetIds) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(assetIds, "assetIds");
        if (assetIds.isEmpty()) {
            return Map.of();
        }
        if (assetIds.size() > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("At most " + MAX_BATCH_SIZE + " tenant assets can be resolved at once");
        }

        Set<TenantAssetId> uniqueIds = new LinkedHashSet<>();
        for (TenantAssetId assetId : assetIds) {
            uniqueIds.add(Objects.requireNonNull(assetId, "assetIds must not contain null"));
        }

        persistence.requireActiveTenant(tenantId);
        return persistence.findAllActiveByIds(tenantId, Set.copyOf(uniqueIds)).stream()
                .peek(record -> requireValidBatchRecord(tenantId, uniqueIds, record))
                .map(this::toApi)
                .collect(Collectors.toUnmodifiableMap(TenantAsset::id, Function.identity()));
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

    private static void requireValidBatchRecord(
            TenantId tenantId,
            Set<TenantAssetId> requestedIds,
            AssetRecord record) {
        if (!tenantId.equals(record.tenantId())
                || !requestedIds.contains(record.id())
                || record.status() != AssetStatus.ACTIVE) {
            throw new IllegalStateException("Asset persistence returned data outside the requested tenant batch");
        }
    }
}
