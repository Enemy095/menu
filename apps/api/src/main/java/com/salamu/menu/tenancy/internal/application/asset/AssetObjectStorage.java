package com.salamu.menu.tenancy.internal.application.asset;

/** AWS-independent output port. Implementations must preserve immutable content-hash semantics. */
public interface AssetObjectStorage {

    void putImmutable(AssetObject object);

    ProvisionedAssetObjectDisposition putProvisionedImmutable(
            AssetObject object,
            String runFingerprint);

    /** Returns false for an absent object and throws on any metadata/provenance mismatch. */
    boolean inspectProvisionedImmutable(
            AssetObjectDescriptor object,
            String runFingerprint);

    /** Returns false when the object is already absent and throws rather than deleting mismatched provenance. */
    boolean deleteProvisionedImmutable(
            AssetObjectDescriptor object,
            String runFingerprint);
}
