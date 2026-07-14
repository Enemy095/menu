package com.salamu.menu.tenancy.internal.infrastructure.config;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

import com.salamu.menu.tenancy.api.TrustedProxyPolicy;

import org.springframework.stereotype.Component;

@Component
final class ConfiguredTrustedProxyPolicy implements TrustedProxyPolicy {

    private final boolean enabled;
    private final List<CidrBlock> trustedNetworks;

    ConfiguredTrustedProxyPolicy(TrustedForwardingProperties properties) {
        enabled = properties.enabled();
        trustedNetworks = properties.trustedProxyCidrs().stream()
                .map(CidrBlock::parse)
                .toList();
    }

    @Override
    public boolean isTrusted(InetAddress remoteAddress) {
        return enabled
                && remoteAddress != null
                && trustedNetworks.stream().anyMatch(network -> network.contains(remoteAddress));
    }

    private record CidrBlock(byte[] network, int prefixLength) {

        private CidrBlock {
            network = network.clone();
        }

        static CidrBlock parse(String value) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("trusted proxy CIDR must not be blank");
            }

            var parts = value.trim().split("/", -1);
            if (parts.length > 2 || !isNumericAddress(parts[0])) {
                throw invalid(value);
            }

            try {
                var address = parseNumericAddress(parts[0]);
                var addressBytes = address.getAddress();
                var totalBits = addressBytes.length * Byte.SIZE;
                var prefix = parts.length == 1 ? totalBits : Integer.parseInt(parts[1]);
                if (prefix < 0 || prefix > totalBits) {
                    throw invalid(value);
                }
                return new CidrBlock(mask(addressBytes, prefix), prefix);
            } catch (UnknownHostException | NumberFormatException exception) {
                throw invalid(value, exception);
            }
        }

        boolean contains(InetAddress candidate) {
            var candidateBytes = candidate.getAddress();
            return candidateBytes.length == network.length
                    && java.util.Arrays.equals(mask(candidateBytes, prefixLength), network);
        }

        @Override
        public byte[] network() {
            return network.clone();
        }

        private static byte[] mask(byte[] address, int prefixLength) {
            var result = address.clone();
            var completeBytes = prefixLength / Byte.SIZE;
            var remainingBits = prefixLength % Byte.SIZE;

            if (remainingBits != 0 && completeBytes < result.length) {
                var mask = 0xFF << (Byte.SIZE - remainingBits);
                result[completeBytes] = (byte) (result[completeBytes] & mask);
                completeBytes++;
            }
            java.util.Arrays.fill(result, completeBytes, result.length, (byte) 0);
            return result;
        }

        private static boolean isNumericAddress(String value) {
            if (value == null || value.isBlank()) {
                return false;
            }
            if (value.indexOf(':') >= 0) {
                return value.chars().allMatch(character ->
                        character == ':' || Character.digit(character, 16) >= 0);
            }
            return value.chars().allMatch(character -> Character.isDigit(character) || character == '.');
        }

        private static InetAddress parseNumericAddress(String value) throws UnknownHostException {
            if (value.indexOf('.') >= 0) {
                var octets = value.split("\\.", -1);
                if (octets.length != 4) {
                    throw new UnknownHostException(value);
                }
                var bytes = new byte[4];
                for (var index = 0; index < octets.length; index++) {
                    var octet = octets[index];
                    if (octet.isEmpty() || octet.length() > 1 && octet.startsWith("0")) {
                        throw new UnknownHostException(value);
                    }
                    var parsed = Integer.parseInt(octet);
                    if (parsed > 255) {
                        throw new UnknownHostException(value);
                    }
                    bytes[index] = (byte) parsed;
                }
                return InetAddress.getByAddress(bytes);
            }
            if (value.indexOf(':') < 0 || value.indexOf('.') >= 0) {
                throw new UnknownHostException(value);
            }
            var address = InetAddress.getByName(value);
            if (address.getAddress().length != 16) {
                throw new UnknownHostException(value);
            }
            return address;
        }

        private static IllegalArgumentException invalid(String value) {
            return new IllegalArgumentException("invalid trusted proxy CIDR: " + value);
        }

        private static IllegalArgumentException invalid(String value, Exception cause) {
            return new IllegalArgumentException("invalid trusted proxy CIDR: " + value, cause);
        }
    }
}
