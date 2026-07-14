package com.salamu.menu.tenancy.internal.domain;

import java.net.IDN;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.regex.Pattern;

public final class HostNormalizer {

    private static final int MAX_HOST_LENGTH = 253;
    private static final Pattern PORT = Pattern.compile("^[0-9]{1,5}$");
    private static final Pattern HOST_LABEL = Pattern.compile("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$");

    public String normalize(String rawHost) {
        if (rawHost == null || rawHost.isBlank()) {
            throw new InvalidHostException();
        }

        var candidate = rawHost.trim();
        rejectUnsafeCharacters(candidate);

        if (candidate.startsWith("[")) {
            return normalizeBracketedIpv6(candidate);
        }

        candidate = stripPort(candidate);
        candidate = stripTrailingDot(candidate);
        if (candidate.indexOf(':') >= 0) {
            return normalizeIpv6(candidate);
        }

        final String ascii;
        try {
            ascii = IDN.toASCII(candidate, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException exception) {
            throw new InvalidHostException(exception);
        }

        validateDnsOrIpv4(ascii);
        return ascii;
    }

    private static String normalizeBracketedIpv6(String candidate) {
        var closingBracket = candidate.indexOf(']');
        if (closingBracket < 0) {
            throw new InvalidHostException();
        }

        var address = candidate.substring(1, closingBracket);
        var suffix = candidate.substring(closingBracket + 1);
        if (!suffix.isEmpty()) {
            if (!suffix.startsWith(":")) {
                throw new InvalidHostException();
            }
            validatePort(suffix.substring(1));
        }
        return normalizeIpv6(address);
    }

    private static String stripPort(String candidate) {
        var colon = candidate.lastIndexOf(':');
        if (colon < 0) {
            return candidate;
        }
        if (candidate.indexOf(':') != colon) {
            return candidate;
        }
        validatePort(candidate.substring(colon + 1));
        return candidate.substring(0, colon);
    }

    private static void validatePort(String value) {
        if (!PORT.matcher(value).matches()) {
            throw new InvalidHostException();
        }
        var port = Integer.parseInt(value);
        if (port < 1 || port > 65_535) {
            throw new InvalidHostException();
        }
    }

    private static String stripTrailingDot(String value) {
        var stripped = value.endsWith(".") ? value.substring(0, value.length() - 1) : value;
        if (stripped.isBlank() || stripped.endsWith(".")) {
            throw new InvalidHostException();
        }
        return stripped;
    }

    private static String normalizeIpv6(String candidate) {
        if (candidate.isBlank() || candidate.indexOf(':') < 0 || candidate.indexOf('%') >= 0) {
            throw new InvalidHostException();
        }
        try {
            var address = InetAddress.getByName(candidate);
            if (address.getAddress().length != 16) {
                throw new InvalidHostException();
            }
            return address.getHostAddress().toLowerCase(Locale.ROOT);
        } catch (UnknownHostException exception) {
            throw new InvalidHostException(exception);
        }
    }

    private static void validateDnsOrIpv4(String hostname) {
        if (hostname.isBlank() || hostname.length() > MAX_HOST_LENGTH) {
            throw new InvalidHostException();
        }

        var labels = hostname.split("\\.", -1);
        var allNumericLabels = java.util.Arrays.stream(labels).allMatch(label ->
                !label.isEmpty() && label.chars().allMatch(Character::isDigit));
        if (allNumericLabels && labels.length != 4) {
            throw new InvalidHostException();
        }
        if (looksLikeIpv4(labels)) {
            for (var label : labels) {
                try {
                    if (label.length() > 1 && label.startsWith("0")
                            || Integer.parseInt(label) > 255) {
                        throw new InvalidHostException();
                    }
                } catch (NumberFormatException exception) {
                    throw new InvalidHostException(exception);
                }
            }
            return;
        }

        for (var label : labels) {
            if (!HOST_LABEL.matcher(label).matches()) {
                throw new InvalidHostException();
            }
        }
    }

    private static boolean looksLikeIpv4(String[] labels) {
        return labels.length == 4
                && java.util.Arrays.stream(labels).allMatch(label ->
                        !label.isEmpty() && label.chars().allMatch(Character::isDigit));
    }

    private static void rejectUnsafeCharacters(String value) {
        if (value.indexOf('/') >= 0
                || value.indexOf('\\') >= 0
                || value.indexOf('@') >= 0
                || value.indexOf(',') >= 0
                || value.chars().anyMatch(character -> Character.isWhitespace(character)
                        || Character.isISOControl(character))) {
            throw new InvalidHostException();
        }
    }

    public static final class InvalidHostException extends IllegalArgumentException {

        public InvalidHostException() {
            super("invalid host");
        }

        public InvalidHostException(Throwable cause) {
            super("invalid host", cause);
        }
    }
}
