package com.salamu.menu.site.api;

import java.net.URI;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

final class SiteValues {

    private static final Pattern LOCALE = Pattern.compile("^[a-z]{2,3}(?:-[A-Z]{2})?$");
    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Set<String> TIMEZONES = Set.copyOf(ZoneId.getAvailableZoneIds());

    private SiteValues() {
    }

    static String required(String value, int minimum, int maximum, String field) {
        Objects.requireNonNull(value, field);
        if (value.length() < minimum || value.length() > maximum || value.isBlank()) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value;
    }

    static String optional(String value, int maximum, String field) {
        if (value != null && value.length() > maximum) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value;
    }

    static String present(String value, int maximum, String field) {
        Objects.requireNonNull(value, field);
        if (value.length() > maximum) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value;
    }

    static String locale(String value) {
        String checked = required(value, 2, 16, "locale");
        if (!LOCALE.matcher(checked).matches()) {
            throw new IllegalArgumentException("locale is invalid");
        }
        return checked;
    }

    static String currency(String value) {
        String checked = required(value, 3, 3, "currency");
        if (!CURRENCY.matcher(checked).matches()) {
            throw new IllegalArgumentException("currency is invalid");
        }
        return checked;
    }

    static String timezone(String value) {
        String checked = required(value, 1, 100, "timezone");
        if (!TIMEZONES.contains(checked)) {
            throw new IllegalArgumentException("timezone is invalid");
        }
        return checked;
    }

    static String email(String value) {
        String checked = optional(value, 254, "publicEmail");
        if (checked != null && !EMAIL.matcher(checked).matches()) {
            throw new IllegalArgumentException("publicEmail is invalid");
        }
        return checked;
    }

    static URI httpUri(URI value, boolean required, String field) {
        if (value == null) {
            if (required) {
                throw new NullPointerException(field);
            }
            return null;
        }
        String scheme = value.getScheme();
        if (!value.isAbsolute()
                || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                || value.getHost() == null
                || value.getUserInfo() != null
                || value.getFragment() != null) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value;
    }

    static <T> List<T> list(List<T> values, String field) {
        List<T> copy = List.copyOf(Objects.requireNonNull(values, field));
        if (copy.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(field + " contains null");
        }
        return copy;
    }
}
