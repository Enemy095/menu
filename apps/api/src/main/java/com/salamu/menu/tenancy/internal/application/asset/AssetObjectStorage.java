package com.salamu.menu.tenancy.internal.application.asset;

/** AWS-independent output port. Implementations must preserve immutable content-hash semantics. */
public interface AssetObjectStorage {

    void putImmutable(AssetObject object);
}
