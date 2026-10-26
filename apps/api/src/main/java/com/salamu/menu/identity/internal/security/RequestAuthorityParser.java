package com.salamu.menu.identity.internal.security;

import java.net.IDN;
import java.util.Locale;
import java.util.Optional;

final class RequestAuthorityParser {

    private static final int MAX_DNS_HOST_LENGTH = 253;

    private RequestAuthorityParser() {
    }

    static Optional<String> normalizeHostLiteral(String value) {
        if (hasForbiddenCharacters(value)) {
            return Optional.empty();
        }
        if (value.startsWith("[")) {
            if (!value.endsWith("]") || value.indexOf(']') != value.length() - 1) {
                return Optional.empty();
            }
            return normalizeIpv6(value.substring(1, value.length() - 1));
        }
        if (value.indexOf(':') >= 0) {
            return Optional.empty();
        }
        return normalizeUnbracketedHost(value);
    }

    static Optional<String> normalizeRequestAuthority(String value) {
        if (hasForbiddenCharacters(value)) {
            return Optional.empty();
        }
        if (value.startsWith("[")) {
            var closingBracket = value.indexOf(']');
            if (closingBracket <= 1) {
                return Optional.empty();
            }
            var suffix = value.substring(closingBracket + 1);
            if (!suffix.isEmpty() && (!suffix.startsWith(":") || !isValidPort(suffix.substring(1)))) {
                return Optional.empty();
            }
            return normalizeIpv6(value.substring(1, closingBracket));
        }

        var firstColon = value.indexOf(':');
        if (firstColon != value.lastIndexOf(':')) {
            return Optional.empty();
        }
        var host = firstColon < 0 ? value : value.substring(0, firstColon);
        if (firstColon >= 0 && !isValidPort(value.substring(firstColon + 1))) {
            return Optional.empty();
        }
        return normalizeUnbracketedHost(host);
    }

    private static Optional<String> normalizeUnbracketedHost(String value) {
        if (value == null || value.isEmpty()) {
            return Optional.empty();
        }
        var host = value;
        if (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
            if (host.endsWith(".")) {
                return Optional.empty();
            }
        }
        if (host.isEmpty()) {
            return Optional.empty();
        }

        if (host.chars().allMatch(character -> Character.isDigit(character) || character == '.')) {
            return TrustedClientIpResolver.parseIpLiteral(host)
                    .filter(address -> address.getAddress().length == 4)
                    .map(address -> address.getHostAddress());
        }

        try {
            var ascii = IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
            if (ascii.isEmpty() || ascii.length() > MAX_DNS_HOST_LENGTH) {
                return Optional.empty();
            }
            for (var label : ascii.split("\\.", -1)) {
                if (label.isEmpty() || label.length() > 63) {
                    return Optional.empty();
                }
            }
            return Optional.of(ascii);
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private static Optional<String> normalizeIpv6(String value) {
        return TrustedClientIpResolver.parseIpLiteral(value)
                .filter(address -> address.getAddress().length == 16)
                .map(address -> "[" + address.getHostAddress().toLowerCase(Locale.ROOT) + "]");
    }

    private static boolean isValidPort(String value) {
        if (value == null || value.isEmpty() || value.length() > 5 || !value.chars().allMatch(Character::isDigit)) {
            return false;
        }
        try {
            var port = Integer.parseInt(value);
            return port >= 1 && port <= 65_535;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private static boolean hasForbiddenCharacters(String value) {
        if (value == null || value.isBlank()) {
            return true;
        }
        for (var index = 0; index < value.length(); index++) {
            var character = value.charAt(index);
            if (Character.isWhitespace(character)
                    || Character.isISOControl(character)
                    || character == '@'
                    || character == '/'
                    || character == '\\'
                    || character == '?'
                    || character == '#'
                    || character == ','
                    || character == '*') {
                return true;
            }
        }
        return false;
    }
}
