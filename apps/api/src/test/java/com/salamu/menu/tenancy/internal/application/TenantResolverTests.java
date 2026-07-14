package com.salamu.menu.tenancy.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.salamu.menu.tenancy.api.TenantId;
import com.salamu.menu.tenancy.api.TenantResolution;
import com.salamu.menu.tenancy.internal.domain.TenantRegistration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TenantResolverTests {

    private static final TenantRegistration STAMBUL = tenant(
            "10000000-0000-4000-8000-000000000001", "stambul");
    private static final TenantRegistration ADAMAS = tenant(
            "20000000-0000-4000-8000-000000000002", "adamas");

    private final InMemoryTenantRegistry registry = new InMemoryTenantRegistry();
    private TenantResolver resolver;

    @BeforeEach
    void setUp() {
        registry.bySlug.put(STAMBUL.slug(), STAMBUL);
        registry.bySlug.put(ADAMAS.slug(), ADAMAS);
        registry.byHostname.put("stambul.platform.example", STAMBUL);
        registry.byHostname.put("custom-restaurant.example", STAMBUL);
        resolver = new TenantResolver(registry, host -> switch (host) {
            case "platform.example" -> PlatformHostPolicy.HostKind.PLATFORM;
            case "localhost", "127.0.0.1" -> PlatformHostPolicy.HostKind.LOCAL;
            default -> PlatformHostPolicy.HostKind.TENANT_DOMAIN;
        });
    }

    @Test
    void resolvesPlatformPathAndLocalAliasBySlug() {
        var platform = resolver.resolve("platform.example", Optional.of("stambul"));
        var local = resolver.resolve("localhost", Optional.of("adamas"));

        assertThat(platform.context()).isEqualTo(STAMBUL.toContext());
        assertThat(platform.source()).isEqualTo(TenantResolution.Source.PLATFORM_PATH);
        assertThat(local.context()).isEqualTo(ADAMAS.toContext());
        assertThat(local.source()).isEqualTo(TenantResolution.Source.LOCAL_PATH);
    }

    @Test
    void resolvesRegisteredSubdomainAndCustomDomain() {
        var subdomain = resolver.resolve("stambul.platform.example", Optional.of("stambul"));
        var custom = resolver.resolve("custom-restaurant.example", Optional.empty());

        assertThat(subdomain.context()).isEqualTo(STAMBUL.toContext());
        assertThat(subdomain.source()).isEqualTo(TenantResolution.Source.DOMAIN);
        assertThat(custom.context()).isEqualTo(STAMBUL.toContext());
        assertThat(custom.source()).isEqualTo(TenantResolution.Source.DOMAIN);
    }

    @Test
    void rejectsHostAndPathSlugMismatch() {
        assertThatThrownBy(() -> resolver.resolve(
                "stambul.platform.example",
                Optional.of("adamas")))
                .isInstanceOf(TenantNotFoundException.class);
    }

    @Test
    void neverFallsBackFromUnknownDomainToPathSlug() {
        assertThatThrownBy(() -> resolver.resolve(
                "unknown.example",
                Optional.of("stambul")))
                .isInstanceOf(TenantNotFoundException.class);
    }

    @Test
    void rejectsMissingUnknownOrInvalidPlatformSlug() {
        assertThatThrownBy(() -> resolver.resolve("platform.example", Optional.empty()))
                .isInstanceOf(TenantNotFoundException.class);
        assertThatThrownBy(() -> resolver.resolve("platform.example", Optional.of("missing")))
                .isInstanceOf(TenantNotFoundException.class);
        assertThatThrownBy(() -> resolver.resolve("platform.example", Optional.of("STAMBUL")))
                .isInstanceOf(TenantNotFoundException.class);
        assertThatThrownBy(() -> resolver.resolve("platform.example", Optional.of("a")))
                .isInstanceOf(TenantNotFoundException.class);
    }

    private static TenantRegistration tenant(String id, String slug) {
        return new TenantRegistration(new TenantId(UUID.fromString(id)), slug);
    }

    private static final class InMemoryTenantRegistry implements TenantRegistry {

        private final Map<String, TenantRegistration> byHostname = new HashMap<>();
        private final Map<String, TenantRegistration> bySlug = new HashMap<>();

        @Override
        public Optional<TenantRegistration> findActiveByHostname(String normalizedHostname) {
            return Optional.ofNullable(byHostname.get(normalizedHostname));
        }

        @Override
        public Optional<TenantRegistration> findActiveBySlug(String normalizedSlug) {
            return Optional.ofNullable(bySlug.get(normalizedSlug));
        }
    }
}
