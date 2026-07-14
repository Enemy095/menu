package com.salamu.menu.tenancy.internal.infrastructure.asset;

final class AssetStorageException extends RuntimeException {

    AssetStorageException(String message) {
        super(message);
    }

    AssetStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
