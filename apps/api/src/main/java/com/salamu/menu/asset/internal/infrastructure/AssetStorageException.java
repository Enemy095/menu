package com.salamu.menu.asset.internal.infrastructure;

public final class AssetStorageException extends RuntimeException {

    private AssetStorageException(String safeMessage) {
        super(safeMessage);
    }

    static AssetStorageException operationFailed() {
        return new AssetStorageException("Asset storage operation failed");
    }

    static AssetStorageException unavailable() {
        return new AssetStorageException("Asset storage object is unavailable");
    }

    static AssetStorageException integrityFailed() {
        return new AssetStorageException("Asset storage integrity verification failed");
    }
}
