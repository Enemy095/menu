package com.salamu.menu.site.api;

import java.net.URI;
import java.util.List;
import java.util.Objects;

public record Branding(
        URI logoUrl,
        String logoAlt,
        URI faviconUrl,
        Hero hero,
        DesignTokens designTokens,
        List<LocalizedSiteText> localizedTexts) {

    public Branding {
        logoUrl = SiteValues.httpUri(logoUrl, true, "logoUrl");
        logoAlt = SiteValues.required(logoAlt, 1, 160, "logoAlt");
        faviconUrl = SiteValues.httpUri(faviconUrl, false, "faviconUrl");
        Objects.requireNonNull(hero, "hero");
        Objects.requireNonNull(designTokens, "designTokens");
        localizedTexts = SiteValues.list(localizedTexts, "localizedTexts");
        if (localizedTexts.isEmpty()) {
            throw new IllegalArgumentException("localizedTexts must not be empty");
        }
    }
}
