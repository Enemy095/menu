package com.salamu.menu.tenancy.internal.application;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

import com.salamu.menu.tenancy.api.TenantResolution;
import com.salamu.menu.tenancy.internal.domain.HostNormalizer;
import com.salamu.menu.tenancy.internal.domain.TenantRegistration;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TenantResolver {

    private static final Pattern TENANT_SLUG = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");

    private final TenantRegistry tenantRegistry;
    private final PlatformHostPolicy platformHostPolicy;

    public TenantResolver(TenantRegistry tenantRegistry, PlatformHostPolicy platformHostPolicy) {
        this.tenantRegistry = tenantRegistry;
        this.platformHostPolicy = platformHostPolicy;
    }

    @Transactional(readOnly = true)
    public TenantResolution resolve(String normalizedHost, Optional<String> pathSlug) {
        Objects.requireNonNull(normalizedHost, "normalizedHost");
        var normalizedSlug = normalizeSlug(pathSlug);
        var hostTenant = tenantRegistry.findActiveByHostname(normalizedHost);

        if (hostTenant.isPresent()) {
            assertMatchingSlug(hostTenant.get(), normalizedSlug);
            return resolution(
                    hostTenant.get(),
                    normalizedHost,
                    normalizedSlug,
                    TenantResolution.Source.DOMAIN);
        }

        var hostKind = platformHostPolicy.classify(normalizedHost);
        if (hostKind == PlatformHostPolicy.HostKind.TENANT_DOMAIN || normalizedSlug.isEmpty()) {
            throw new TenantNotFoundException();
        }

        var slugTenant = tenantRegistry.findActiveBySlug(normalizedSlug.orElseThrow())
                .orElseThrow(TenantNotFoundException::new);
        var source = hostKind == PlatformHostPolicy.HostKind.LOCAL
                ? TenantResolution.Source.LOCAL_PATH
                : TenantResolution.Source.PLATFORM_PATH;
        return resolution(slugTenant, normalizedHost, normalizedSlug, source);
    }

    private static TenantResolution resolution(
            TenantRegistration registration,
            String normalizedHost,
            Optional<String> normalizedSlug,
            TenantResolution.Source source) {
        return new TenantResolution(
                registration.toContext(),
                normalizedHost,
                normalizedSlug,
                source);
    }

    private static Optional<String> normalizeSlug(Optional<String> pathSlug) {
        if (pathSlug == null || pathSlug.isEmpty()) {
            return Optional.empty();
        }
        var candidate = pathSlug.orElseThrow();
        if (candidate.length() < 2
                || candidate.length() > 63
                || !TENANT_SLUG.matcher(candidate).matches()) {
            throw new TenantNotFoundException();
        }
        return Optional.of(candidate);
    }

    private static void assertMatchingSlug(
            TenantRegistration hostTenant,
            Optional<String> normalizedSlug) {
        if (normalizedSlug.isEmpty()) {
            return;
        }
        if (!hostTenant.slug().equals(normalizedSlug.orElseThrow())) {
            throw new TenantNotFoundException();
        }
    }
}
