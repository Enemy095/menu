package com.salamu.menu.tenancy.internal.application.asset;

import java.net.URI;

public interface AssetPublicUrlResolver {

    URI resolve(AssetObjectKey objectKey);
}
