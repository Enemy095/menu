package com.salamu.menu.identity.internal.security;

import java.net.URI;
import java.time.Duration;
import java.util.Set;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.security", ignoreUnknownFields = false)
public record SecurityProperties(
        @NotNull @Valid Cors cors,
        @NotNull @Valid RequestAuthority requestAuthority,
        @NotNull @Valid TrustedProxies trustedProxies,
        @NotNull @Valid RateLimit rateLimit) {

    public record Cors(Set<@NotBlank String> allowedOrigins) {

        public Cors {
            allowedOrigins = immutableSet(allowedOrigins);
        }

        @AssertTrue(message = "CORS origins must be exact HTTP(S) origins without wildcard, credentials, or path")
        public boolean isExactOriginAllowlist() {
            return allowedOrigins.stream().allMatch(Cors::isExactHttpOrigin);
        }

        private static boolean isExactHttpOrigin(String value) {
            if (value == null || value.isBlank() || value.contains("*")) {
                return false;
            }
            try {
                var origin = URI.create(value);
                var scheme = origin.getScheme();
                var path = origin.getRawPath();
                var port = origin.getPort();
                return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                        && origin.getHost() != null
                        && origin.getRawUserInfo() == null
                        && (path == null || path.isEmpty())
                        && origin.getRawQuery() == null
                        && origin.getRawFragment() == null
                        && (port == -1 || port >= 1 && port <= 65_535);
            } catch (IllegalArgumentException exception) {
                return false;
            }
        }
    }

    public record RequestAuthority(Set<@NotBlank String> allowedHosts) {

        public RequestAuthority {
            allowedHosts = immutableSet(allowedHosts);
        }

        @AssertTrue(message = "request authority hosts must be exact host literals without scheme, port, wildcard, or path")
        public boolean isExactHostAllowlist() {
            return allowedHosts.stream()
                    .allMatch(host -> RequestAuthorityParser.normalizeHostLiteral(host).isPresent());
        }
    }

    public record TrustedProxies(
            boolean enabled,
            Set<@NotBlank String> cidrs) {

        public TrustedProxies {
            cidrs = immutableSet(cidrs);
        }

        @AssertTrue(message = "trusted proxy CIDRs must contain only valid IPv4 or IPv6 CIDR literals")
        public boolean isCidrAllowlistValid() {
            return cidrs.stream().allMatch(TrustedProxyPolicy::isValidCidr);
        }
    }

    public record RateLimit(
            @NotNull @Valid Limit client,
            @NotNull @Valid Limit application,
            @NotNull @Valid Cache cache) {
    }

    public record Limit(
            @Min(1) long tokensPerPeriod,
            @NotNull Duration period,
            @Min(1) long burstCapacity) {

        @AssertTrue(message = "rate-limit period must be positive")
        public boolean isPeriodPositive() {
            return period != null && !period.isZero() && !period.isNegative();
        }

        @AssertTrue(message = "burst capacity must be at least tokens per period")
        public boolean isBurstCapacityValid() {
            return burstCapacity >= tokensPerPeriod;
        }
    }

    public record Cache(
            @Min(1) long maximumClientBuckets,
            @NotNull Duration expireAfterAccess) {

        @AssertTrue(message = "rate-limit cache expiry must be positive")
        public boolean isExpiryPositive() {
            return expireAfterAccess != null
                    && !expireAfterAccess.isZero()
                    && !expireAfterAccess.isNegative();
        }
    }

    private static Set<String> immutableSet(Set<String> values) {
        return values == null ? Set.of() : Set.copyOf(values);
    }
}
