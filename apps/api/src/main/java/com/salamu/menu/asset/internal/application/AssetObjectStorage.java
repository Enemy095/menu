package com.salamu.menu.asset.internal.application;

public interface AssetObjectStorage {

    void putImmutable(AssetObject object);

    void requirePresent(AssetObjectDescriptor object);
}
