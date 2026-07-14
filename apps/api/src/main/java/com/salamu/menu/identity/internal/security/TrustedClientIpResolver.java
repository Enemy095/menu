package com.salamu.menu.identity.internal.security;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;

import com.salamu.menu.tenancy.api.TrustedProxyPolicy;

import org.springframework.stereotype.Component;

@Component
public class TrustedClientIpResolver {

    static final String FORWARDED_FOR_HEADER = "X-Forwarded-For";
    private static final int MAX_FORWARDED_HEADER_LENGTH = 512;
    private static final int MAX_FORWARDED_HOPS = 16;
    private static final String INVALID_REMOTE_ADDRESS = "invalid-remote";

    private final TrustedProxyPolicy trustedProxyPolicy;

    public TrustedClientIpResolver(TrustedProxyPolicy trustedProxyPolicy) {
        this.trustedProxyPolicy = trustedProxyPolicy;
    }

    public String resolve(HttpServletRequest request) {
        var remoteAddress = parseIpLiteral(request.getRemoteAddr());
        if (remoteAddress.isEmpty()) {
            return INVALID_REMOTE_ADDRESS;
        }

        var directPeer = remoteAddress.get();
        var directPeerKey = canonicalAddress(directPeer);
        if (!trustedProxyPolicy.isTrusted(directPeer)) {
            return directPeerKey;
        }

        var forwardedHeaders = Collections.list(request.getHeaders(FORWARDED_FOR_HEADER));
        if (forwardedHeaders.size() != 1) {
            return directPeerKey;
        }

        var forwardedHeader = forwardedHeaders.getFirst();
        if (forwardedHeader == null
                || forwardedHeader.isBlank()
                || forwardedHeader.length() > MAX_FORWARDED_HEADER_LENGTH) {
            return directPeerKey;
        }

        var forwardedAddresses = forwardedHeader.split(",", -1);
        if (forwardedAddresses.length > MAX_FORWARDED_HOPS) {
            return directPeerKey;
        }

        for (var index = forwardedAddresses.length - 1; index >= 0; index--) {
            var candidate = parseIpLiteral(forwardedAddresses[index].trim());
            if (candidate.isEmpty()) {
                return directPeerKey;
            }
            if (!trustedProxyPolicy.isTrusted(candidate.get())) {
                return canonicalAddress(candidate.get());
            }
        }

        return directPeerKey;
    }

    static Optional<InetAddress> parseIpLiteral(String value) {
        if (value == null || value.isBlank() || value.length() > 64) {
            return Optional.empty();
        }
        if (value.indexOf(':') < 0) {
            return parseIpv4(value);
        }
        if (!value.matches("[0-9A-Fa-f:.]+")) {
            return Optional.empty();
        }
        try {
            var address = InetAddress.getByName(value);
            return address.getAddress().length == 16 ? Optional.of(address) : Optional.empty();
        } catch (UnknownHostException exception) {
            return Optional.empty();
        }
    }

    private static Optional<InetAddress> parseIpv4(String value) {
        var octets = value.split("\\.", -1);
        if (octets.length != 4) {
            return Optional.empty();
        }
        var address = new byte[4];
        for (var index = 0; index < octets.length; index++) {
            var octet = octets[index];
            if (octet.isEmpty()
                    || octet.length() > 3
                    || octet.length() > 1 && octet.startsWith("0")
                    || !octet.chars().allMatch(Character::isDigit)) {
                return Optional.empty();
            }
            var number = Integer.parseInt(octet);
            if (number > 255) {
                return Optional.empty();
            }
            address[index] = (byte) number;
        }
        try {
            return Optional.of(InetAddress.getByAddress(address));
        } catch (UnknownHostException exception) {
            return Optional.empty();
        }
    }

    private static String canonicalAddress(InetAddress address) {
        return address.getHostAddress();
    }
}
