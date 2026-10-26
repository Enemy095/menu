package com.salamu.menu.site.api;

import java.util.regex.Pattern;

public record DesignTokens(
        String primaryColor,
        String secondaryColor,
        String accentColor,
        String backgroundColor,
        String surfaceColor,
        String textColor,
        String mutedTextColor,
        String borderRadius,
        String fontFamily) {

    private static final Pattern COLOR = Pattern.compile("^#[0-9A-Fa-f]{6}$");
    private static final Pattern RADIUS = Pattern.compile("^[0-9]+(?:\\.[0-9]+)?(?:px|rem)$");

    public DesignTokens {
        primaryColor = color(primaryColor, "primaryColor");
        secondaryColor = color(secondaryColor, "secondaryColor");
        accentColor = color(accentColor, "accentColor");
        backgroundColor = color(backgroundColor, "backgroundColor");
        surfaceColor = color(surfaceColor, "surfaceColor");
        textColor = color(textColor, "textColor");
        mutedTextColor = color(mutedTextColor, "mutedTextColor");
        borderRadius = SiteValues.required(borderRadius, 1, 32, "borderRadius");
        if (!RADIUS.matcher(borderRadius).matches()) {
            throw new IllegalArgumentException("borderRadius is invalid");
        }
        fontFamily = SiteValues.required(fontFamily, 1, 120, "fontFamily");
    }

    private static String color(String value, String field) {
        String checked = SiteValues.required(value, 7, 7, field);
        if (!COLOR.matcher(checked).matches()) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return checked;
    }
}
