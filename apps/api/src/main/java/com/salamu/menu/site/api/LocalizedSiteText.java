package com.salamu.menu.site.api;

public record LocalizedSiteText(
        String locale,
        String displayName,
        String shortName,
        String businessTypeLabel,
        String cuisineText,
        String slogan,
        String introText,
        String menuSectionTitle,
        String cartTitle,
        String orderTypeLegend) {

    public LocalizedSiteText {
        locale = SiteValues.locale(locale);
        displayName = SiteValues.required(displayName, 1, 120, "displayName");
        shortName = SiteValues.required(shortName, 1, 80, "shortName");
        businessTypeLabel = SiteValues.required(businessTypeLabel, 1, 80, "businessTypeLabel");
        cuisineText = SiteValues.present(cuisineText, 160, "cuisineText");
        slogan = SiteValues.present(slogan, 200, "slogan");
        introText = SiteValues.present(introText, 500, "introText");
        menuSectionTitle = SiteValues.required(menuSectionTitle, 1, 120, "menuSectionTitle");
        cartTitle = SiteValues.required(cartTitle, 1, 120, "cartTitle");
        orderTypeLegend = SiteValues.required(orderTypeLegend, 1, 160, "orderTypeLegend");
    }
}
