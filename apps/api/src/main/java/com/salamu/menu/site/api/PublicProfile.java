package com.salamu.menu.site.api;

import java.util.List;

public record PublicProfile(
        String publicPhone,
        String publicPhoneLabel,
        String publicEmail,
        String address,
        GeoLocation location,
        List<SocialLink> socialLinks) {

    public PublicProfile {
        publicPhone = SiteValues.required(publicPhone, 5, 32, "publicPhone");
        publicPhoneLabel = SiteValues.required(publicPhoneLabel, 1, 80, "publicPhoneLabel");
        publicEmail = SiteValues.email(publicEmail);
        address = SiteValues.optional(address, 240, "address");
        socialLinks = SiteValues.list(socialLinks, "socialLinks");
        if (socialLinks.size() > 20) {
            throw new IllegalArgumentException("socialLinks exceed the accepted bound");
        }
    }
}
