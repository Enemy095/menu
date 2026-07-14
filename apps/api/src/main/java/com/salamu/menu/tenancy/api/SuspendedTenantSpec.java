package com.salamu.menu.tenancy.api;

import java.time.ZoneId;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Immutable identity fields used to create or compare a non-public tenant skeleton. */
public record SuspendedTenantSpec(String slug, String locale, String currency, String timezone) {

    private static final Pattern SLUG = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");
    private static final Pattern LOCALE = Pattern.compile("^[a-z]{2,3}(?:-[A-Z]{2})?$");
    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");
    private static final Set<String> TIMEZONES = Set.copyOf(ZoneId.getAvailableZoneIds());

    public SuspendedTenantSpec {
        slug = requirePattern(slug, SLUG, 63, "slug");
        locale = requirePattern(locale, LOCALE, 16, "locale");
        currency = requirePattern(currency, CURRENCY, 3, "currency");
        timezone = Objects.requireNonNull(timezone, "timezone");
        if (timezone.length() > 64 || !TIMEZONES.contains(timezone)) {
            throw new IllegalArgumentException("timezone must be a supported IANA identifier");
        }
        // Reject language tags that merely match the storage regex but are not structurally valid.
        if (Locale.forLanguageTag(locale).getLanguage().isBlank()) {
            throw new IllegalArgumentException("locale must be a valid language tag");
        }
    }

    private static String requirePattern(
            String value,
            Pattern pattern,
            int maximumLength,
            String field) {
        Objects.requireNonNull(value, field);
        if (value.length() > maximumLength || !pattern.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " has an invalid canonical value");
        }
        return value;
    }
}
