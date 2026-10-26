package com.salamu.menu.asset.internal.application;

import java.net.URI;

public interface AssetPublicUrlResolver {

    URI resolve(AssetObjectKey objectKey);
}
