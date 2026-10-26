package com.salamu.menu.identity.internal.security;

import java.net.InetAddress;
import java.util.List;

import org.springframework.stereotype.Component;

@Component
public class TrustedProxyPolicy {

    private final boolean enabled;
    private final List<CidrBlock> trustedCidrs;

    public TrustedProxyPolicy(SecurityProperties properties) {
        var configuration = properties.trustedProxies();
        enabled = configuration.enabled();
        trustedCidrs = configuration.cidrs().stream()
                .map(CidrBlock::parse)
                .toList();
    }

    public boolean isTrusted(InetAddress address) {
        return enabled && address != null && trustedCidrs.stream().anyMatch(cidr -> cidr.contains(address));
    }

    static boolean isValidCidr(String value) {
        try {
            CidrBlock.parse(value);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private record CidrBlock(byte[] network, int prefixLength) {

        private CidrBlock {
            network = network.clone();
        }

        static CidrBlock parse(String value) {
            if (value == null || value.isBlank() || value.indexOf('/') != value.lastIndexOf('/')) {
                throw new IllegalArgumentException("Invalid CIDR");
            }
            var separator = value.indexOf('/');
            if (separator <= 0 || separator == value.length() - 1) {
                throw new IllegalArgumentException("Invalid CIDR");
            }
            var address = TrustedClientIpResolver.parseIpLiteral(value.substring(0, separator))
                    .orElseThrow(() -> new IllegalArgumentException("Invalid CIDR address"));
            int prefix;
            try {
                var rawPrefix = value.substring(separator + 1);
                if (!rawPrefix.chars().allMatch(Character::isDigit)) {
                    throw new IllegalArgumentException("Invalid CIDR prefix");
                }
                prefix = Integer.parseInt(rawPrefix);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("Invalid CIDR prefix", exception);
            }
            var bytes = address.getAddress();
            if (prefix < 0 || prefix > bytes.length * Byte.SIZE) {
                throw new IllegalArgumentException("Invalid CIDR prefix");
            }
            mask(bytes, prefix);
            return new CidrBlock(bytes, prefix);
        }

        boolean contains(InetAddress address) {
            var candidate = address.getAddress().clone();
            if (candidate.length != network.length) {
                return false;
            }
            mask(candidate, prefixLength);
            return java.util.Arrays.equals(network, candidate);
        }

        private static void mask(byte[] bytes, int prefixLength) {
            var fullBytes = prefixLength / Byte.SIZE;
            var remainingBits = prefixLength % Byte.SIZE;
            if (remainingBits != 0 && fullBytes < bytes.length) {
                var mask = 0xFF << (Byte.SIZE - remainingBits);
                bytes[fullBytes] = (byte) (bytes[fullBytes] & mask);
                fullBytes++;
            }
            java.util.Arrays.fill(bytes, fullBytes, bytes.length, (byte) 0);
        }
    }
}
