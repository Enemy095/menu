package com.salamu.menu.site.api;

import java.net.URI;
import java.util.List;

public record Hero(
        URI imageUrl,
        String imageAlt,
        String eyebrow,
        String title,
        String subtitle,
        String ctaLabel,
        List<HeroBenefit> benefits) {

    public Hero {
        imageUrl = SiteValues.httpUri(imageUrl, false, "imageUrl");
        imageAlt = SiteValues.optional(imageAlt, 160, "imageAlt");
        eyebrow = SiteValues.optional(eyebrow, 100, "eyebrow");
        title = SiteValues.required(title, 1, 160, "title");
        subtitle = SiteValues.optional(subtitle, 240, "subtitle");
        ctaLabel = SiteValues.required(ctaLabel, 1, 80, "ctaLabel");
        benefits = SiteValues.list(benefits, "benefits");
        if (benefits.size() > 6) {
            throw new IllegalArgumentException("benefits exceed the accepted bound");
        }
    }
}
