package com.salamu.menu.asset.internal.application;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.salamu.menu.asset.api.AssetCatalog;
import com.salamu.menu.asset.api.AssetId;
import com.salamu.menu.asset.api.AssetUnavailableException;
import com.salamu.menu.asset.api.PublicAsset;

public final class AssetCatalogService implements AssetCatalog {

    public static final int MAX_BATCH_SIZE = 500;

    private final AssetMetadataRepository repository;
    private final AssetPublicUrlResolver publicUrlResolver;

    public AssetCatalogService(
            AssetMetadataRepository repository,
            AssetPublicUrlResolver publicUrlResolver) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.publicUrlResolver = Objects.requireNonNull(publicUrlResolver, "publicUrlResolver");
    }

    @Override
    public PublicAsset requireActive(AssetId id) {
        Objects.requireNonNull(id, "id");
        return requireActive(List.of(id)).get(id);
    }

    @Override
    public Map<AssetId, PublicAsset> requireActive(Collection<AssetId> ids) {
        Objects.requireNonNull(ids, "ids");
        LinkedHashSet<AssetId> requested = new LinkedHashSet<>();
        for (AssetId id : ids) {
            requested.add(Objects.requireNonNull(id, "ids must not contain null"));
            if (requested.size() > MAX_BATCH_SIZE) {
                throw new IllegalArgumentException("At most 500 unique asset IDs can be resolved at once");
            }
        }
        if (requested.isEmpty()) {
            return Map.of();
        }

        List<AssetRecord> records = repository.findAllActiveByIds(Set.copyOf(requested));
        if (records == null) {
            throw unavailable();
        }

        Map<AssetId, AssetRecord> recordsById = new LinkedHashMap<>();
        for (AssetRecord record : new ArrayList<>(records)) {
            if (record == null
                    || !record.isActive()
                    || !requested.contains(record.id())
                    || recordsById.putIfAbsent(record.id(), record) != null) {
                throw unavailable();
            }
        }
        if (recordsById.size() != requested.size()) {
            throw unavailable();
        }

        Map<AssetId, PublicAsset> result = new LinkedHashMap<>();
        for (AssetId id : requested) {
            AssetRecord record = recordsById.get(id);
            result.put(id, new PublicAsset(
                    record.id(),
                    record.kind(),
                    publicUrlResolver.resolve(record.objectKey()),
                    record.sha256().value()));
        }
        return Collections.unmodifiableMap(result);
    }

    private static AssetUnavailableException unavailable() {
        return new AssetUnavailableException();
    }
}
