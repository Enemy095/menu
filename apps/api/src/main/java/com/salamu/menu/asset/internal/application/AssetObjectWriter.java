package com.salamu.menu.asset.internal.application;

import java.util.Objects;

/**
 * Internal write boundary. It deliberately is not part of {@code asset::api}; an
 * authorized administration use case can invoke it in a later migration slice.
 */
public final class AssetObjectWriter {

    private final AssetContentPolicy contentPolicy;
    private final AssetObjectStorage objectStorage;

    public AssetObjectWriter(AssetContentPolicy contentPolicy, AssetObjectStorage objectStorage) {
        this.contentPolicy = Objects.requireNonNull(contentPolicy, "contentPolicy");
        this.objectStorage = Objects.requireNonNull(objectStorage, "objectStorage");
    }

    public AssetObjectDescriptor validateAndStore(AssetUploadContent upload) {
        AssetObject object = contentPolicy.validate(upload).toObject();
        objectStorage.putImmutable(object);
        return object.descriptor();
    }
}
