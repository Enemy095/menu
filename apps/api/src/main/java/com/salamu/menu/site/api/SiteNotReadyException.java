package com.salamu.menu.site.api;

/** Public-safe failure signal. It intentionally carries no stored value, identifier, or cause. */
public final class SiteNotReadyException extends RuntimeException {

    public SiteNotReadyException() {
        super("Public site configuration is not ready");
    }
}
