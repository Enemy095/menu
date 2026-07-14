package com.salamu.menu.site.internal.application;

public final class SiteConfigurationCorruptException extends RuntimeException {

    public SiteConfigurationCorruptException() {
        super("Stored site configuration is invalid");
    }
}
