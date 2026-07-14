package com.salamu.menu.site.api;

public record HeroBenefit(String icon, String text) {

    public HeroBenefit {
        icon = SiteValues.required(icon, 1, 16, "icon");
        text = SiteValues.required(text, 1, 100, "text");
    }
}
