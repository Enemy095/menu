package com.salamu.menu.asset.internal.application;

import java.util.List;
import java.util.Set;

import com.salamu.menu.asset.api.AssetId;

/**
 * Persistence port for the final ownerless {@code assets} table. Implementations must
 * return only active rows and perform one bounded batch query.
 */
public interface AssetMetadataRepository {

    List<AssetRecord> findAllActiveByIds(Set<AssetId> ids);
}
