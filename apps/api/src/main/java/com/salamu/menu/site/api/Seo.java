package com.salamu.menu.site.api;

import java.net.URI;

public record Seo(String title, String description, URI canonicalUrl, URI openGraphImageUrl) {

    public Seo {
        title = SiteValues.required(title, 1, 120, "title");
        description = SiteValues.required(description, 1, 320, "description");
        canonicalUrl = SiteValues.httpUri(canonicalUrl, true, "canonicalUrl");
        openGraphImageUrl = SiteValues.httpUri(openGraphImageUrl, false, "openGraphImageUrl");
    }
}
