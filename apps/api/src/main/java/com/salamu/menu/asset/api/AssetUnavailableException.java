package com.salamu.menu.asset.api;

/**
 * Signals absent, deleted, or internally inconsistent asset configuration without
 * exposing storage coordinates or resource identifiers.
 */
public final class AssetUnavailableException extends RuntimeException {

    private static final String SAFE_MESSAGE = "Required asset is unavailable";

    public AssetUnavailableException() {
        super(SAFE_MESSAGE);
    }
}
