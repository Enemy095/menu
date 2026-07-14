package com.salamu.menu.site.api;

import java.net.URI;
import java.util.Objects;

public record SocialLink(SocialLinkType type, String label, URI url) {

    public SocialLink {
        Objects.requireNonNull(type, "type");
        label = SiteValues.required(label, 1, 80, "label");
        url = SiteValues.httpUri(url, true, "url");
    }
}
