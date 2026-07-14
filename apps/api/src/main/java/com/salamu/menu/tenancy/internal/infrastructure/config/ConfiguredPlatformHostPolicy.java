package com.salamu.menu.tenancy.internal.infrastructure.config;

import java.util.LinkedHashSet;
import java.util.Set;

import com.salamu.menu.tenancy.internal.application.PlatformHostPolicy;
import com.salamu.menu.tenancy.internal.domain.HostNormalizer;

import org.springframework.stereotype.Component;

@Component
final class ConfiguredPlatformHostPolicy implements PlatformHostPolicy {

    private final Set<String> platformHosts;
    private final Set<String> localHosts;

    ConfiguredPlatformHostPolicy(PlatformHostsProperties properties) {
        var normalizer = new HostNormalizer();
        var normalizedPlatformHosts = new LinkedHashSet<String>();
        normalizedPlatformHosts.add(normalizer.normalize(properties.baseDomain()));
        properties.allowedHosts().stream()
                .map(normalizer::normalize)
                .forEach(normalizedPlatformHosts::add);
        platformHosts = Set.copyOf(normalizedPlatformHosts);
        localHosts = properties.localAliases().stream()
                .map(normalizer::normalize)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());

        if (platformHosts.stream().anyMatch(localHosts::contains)) {
            throw new IllegalArgumentException("platform hosts and local aliases must not overlap");
        }
    }

    @Override
    public HostKind classify(String normalizedHost) {
        if (localHosts.contains(normalizedHost)) {
            return HostKind.LOCAL;
        }
        if (platformHosts.contains(normalizedHost)) {
            return HostKind.PLATFORM;
        }
        return HostKind.TENANT_DOMAIN;
    }
}
