package com.salamu.menu.asset.api;

import java.util.Collection;
import java.util.Map;

public interface AssetCatalog {

    PublicAsset requireActive(AssetId id);

    Map<AssetId, PublicAsset> requireActive(Collection<AssetId> ids);
}
