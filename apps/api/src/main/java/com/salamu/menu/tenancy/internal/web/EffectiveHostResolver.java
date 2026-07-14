package com.salamu.menu.tenancy.internal.web;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;

import com.salamu.menu.tenancy.api.TrustedProxyPolicy;
import com.salamu.menu.tenancy.internal.domain.HostNormalizer;

import org.springframework.stereotype.Component;

@Component
final class EffectiveHostResolver {

    private static final Pattern FORWARDED_HOST = Pattern.compile(
            "(?i)(?:^|;)\\s*host=(?:\"([^\"]+)\"|([^;,\\s]+))");

    private final TrustedProxyPolicy trustedProxyPolicy;
    private final HostNormalizer hostNormalizer = new HostNormalizer();

    EffectiveHostResolver(TrustedProxyPolicy trustedProxyPolicy) {
        this.trustedProxyPolicy = trustedProxyPolicy;
    }

    String resolve(HttpServletRequest request) {
        var host = directHost(request);
        var remoteAddress = numericAddress(request.getRemoteAddr());
        if (trustedProxyPolicy.isTrusted(remoteAddress)) {
            host = forwardedHost(request, host);
        }
        return hostNormalizer.normalize(host);
    }

    private static String directHost(HttpServletRequest request) {
        var hostHeader = singleHeader(request, "Host");
        return hostHeader == null || hostHeader.isBlank() ? request.getServerName() : hostHeader;
    }

    private static String forwardedHost(HttpServletRequest request, String fallback) {
        var forwarded = singleHeader(request, "Forwarded");
        if (forwarded != null && !forwarded.isBlank()) {
            rejectMultipleEntries(forwarded);
            var matcher = FORWARDED_HOST.matcher(forwarded);
            if (matcher.find()) {
                var host = matcher.group(1) == null ? matcher.group(2) : matcher.group(1);
                if (matcher.find()) {
                    throw new HostNormalizer.InvalidHostException();
                }
                return host;
            }
        }

        var legacyForwardedHost = singleHeader(request, "X-Forwarded-Host");
        if (legacyForwardedHost != null && !legacyForwardedHost.isBlank()) {
            rejectMultipleEntries(legacyForwardedHost);
            return legacyForwardedHost.trim();
        }
        return fallback;
    }

    private static String singleHeader(HttpServletRequest request, String name) {
        var values = java.util.Collections.list(request.getHeaders(name));
        if (values.size() > 1) {
            throw new HostNormalizer.InvalidHostException();
        }
        return values.isEmpty() ? null : values.getFirst();
    }

    private static void rejectMultipleEntries(String value) {
        if (value.indexOf(',') >= 0) {
            throw new HostNormalizer.InvalidHostException();
        }
    }

    private static InetAddress numericAddress(String value) {
        if (value == null
                || value.isBlank()
                || !value.chars().allMatch(character ->
                        Character.isDigit(character) || character == '.' || character == ':')) {
            return null;
        }
        try {
            if (value.indexOf('.') >= 0) {
                var octets = value.split("\\.", -1);
                if (octets.length != 4) {
                    return null;
                }
                var bytes = new byte[4];
                for (var index = 0; index < octets.length; index++) {
                    var octet = octets[index];
                    if (octet.isEmpty() || octet.length() > 1 && octet.startsWith("0")) {
                        return null;
                    }
                    var parsed = Integer.parseInt(octet);
                    if (parsed > 255) {
                        return null;
                    }
                    bytes[index] = (byte) parsed;
                }
                return InetAddress.getByAddress(bytes);
            }
            if (value.indexOf(':') < 0) {
                return null;
            }
            var address = InetAddress.getByName(value);
            return address.getAddress().length == 16 ? address : null;
        } catch (UnknownHostException | NumberFormatException exception) {
            return null;
        }
    }
}
