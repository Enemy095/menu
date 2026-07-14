package com.salamu.menu.restaurant.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.salamu.menu.restaurant.internal.application.PublicRestaurantConfigurationRepository.StoredConfiguration;
import com.salamu.menu.restaurant.internal.application.PublicRestaurantConfigurationRepository.StoredFeature;
import com.salamu.menu.tenancy.api.AssetKind;
import com.salamu.menu.tenancy.api.TenantAsset;
import com.salamu.menu.tenancy.api.TenantAssetId;
import com.salamu.menu.tenancy.api.TenantAssetService;
import com.salamu.menu.tenancy.api.TenantContext;
import com.salamu.menu.tenancy.api.TenantId;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

class PublicRestaurantQueryTests {

    private static final TenantId TENANT_ID = new TenantId(
            UUID.fromString("10000000-0000-4000-8000-000000000001"));
    private static final TenantContext CONTEXT = new TenantContext(TENANT_ID, "stambul");
    private static final TenantAssetId LOGO_ID = assetId("11000000-0000-4000-8000-000000000001");
    private static final TenantAssetId HERO_ID = assetId("12000000-0000-4000-8000-000000000001");
    private static final OffsetDateTime REVISION = OffsetDateTime.of(
            2026, 7, 14, 12, 0, 0, 0, ZoneOffset.UTC);

    private PublicRestaurantConfigurationRepository repository;
    private TenantAssetService assetService;
    private PublicRestaurantQuery query;

    @BeforeEach
    void setUp() {
        repository = mock(PublicRestaurantConfigurationRepository.class);
        assetService = mock(TenantAssetService.class);
        query = new PublicRestaurantQuery(repository, assetService);
    }

    @Test
    void orchestratesTwoRepositoryCallsAndOneTenantScopedAssetBatch() {
        var stored = storedConfiguration(LOGO_ID, HERO_ID);
        var features = features();
        when(repository.findActiveDefault(TENANT_ID)).thenReturn(Optional.of(stored));
        when(repository.findFeatures(TENANT_ID)).thenReturn(features);
        when(assetService.getActiveAssets(eq(TENANT_ID), org.mockito.ArgumentMatchers.anyCollection()))
                .thenReturn(Map.of(
                        LOGO_ID, asset(LOGO_ID, AssetKind.LOGO),
                        HERO_ID, asset(HERO_ID, AssetKind.HERO)));

        var result = query.get(CONTEXT);

        assertThat(result.slug()).isEqualTo("stambul");
        assertThat(result.branding().logoUrl()).hasToString("https://assets.example.test/logo.png");
        assertThat(result.branding().logoAlt()).isEqualTo("У Ризвана");
        assertThat(result.branding().hero().imageUrl()).hasToString("https://assets.example.test/hero.png");
        assertThat(result.orderTypes()).extracting(PublicRestaurantConfiguration.AvailableOrderType::code)
                .containsExactly("preorder", "delivery", "pickup");
        verify(repository).findActiveDefault(TENANT_ID);
        verify(repository).findFeatures(TENANT_ID);
        @SuppressWarnings("unchecked")
        var ids = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(assetService).getActiveAssets(eq(TENANT_ID), ids.capture());
        assertThat(ids.getValue()).containsExactly(LOGO_ID, HERO_ID);
    }

    @Test
    void returnsNotFoundOnlyWhenActiveDefaultConfigurationDoesNotExist() {
        when(repository.findActiveDefault(TENANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> query.get(CONTEXT))
                .isInstanceOf(RestaurantConfigurationNotFoundException.class);
        verify(repository, never()).findFeatures(TENANT_ID);
        verify(assetService, never()).getActiveAssets(eq(TENANT_ID), org.mockito.ArgumentMatchers.anyCollection());
    }

    @Test
    void treatsMissingOrWrongKindConfiguredAssetsAsCorruption() {
        when(repository.findActiveDefault(TENANT_ID)).thenReturn(Optional.of(storedConfiguration(LOGO_ID, HERO_ID)));
        when(repository.findFeatures(TENANT_ID)).thenReturn(features());
        when(assetService.getActiveAssets(eq(TENANT_ID), org.mockito.ArgumentMatchers.anyCollection()))
                .thenReturn(Map.of(
                        LOGO_ID, asset(LOGO_ID, AssetKind.FAVICON),
                        HERO_ID, asset(HERO_ID, AssetKind.HERO)));

        assertThatThrownBy(() -> query.get(CONTEXT))
                .isInstanceOf(RestaurantConfigurationCorruptException.class);
    }

    @Test
    void failsClosedForInvalidOrderDefaultsBeforeAssetLookup() {
        var invalid = List.of(
                feature("orders", true, null, false, limits()),
                feature("preorder", true, "Предзаказ", true, null),
                feature("delivery", true, "Доставка", true, null));
        when(repository.findActiveDefault(TENANT_ID)).thenReturn(Optional.of(storedConfiguration(LOGO_ID, null)));
        when(repository.findFeatures(TENANT_ID)).thenReturn(invalid);

        assertThatThrownBy(() -> query.get(CONTEXT))
                .isInstanceOf(RestaurantConfigurationCorruptException.class);
        verify(assetService, never()).getActiveAssets(eq(TENANT_ID), org.mockito.ArgumentMatchers.anyCollection());
    }

    @Test
    void etagIsIndependentOfFeatureAndAssetMapIterationOrder() {
        var stored = storedConfiguration(LOGO_ID, HERO_ID);
        var forwardFeatures = features();
        var reverseFeatures = forwardFeatures.reversed();
        var forwardAssets = new LinkedHashMap<TenantAssetId, TenantAsset>();
        forwardAssets.put(LOGO_ID, asset(LOGO_ID, AssetKind.LOGO));
        forwardAssets.put(HERO_ID, asset(HERO_ID, AssetKind.HERO));
        var reverseAssets = new LinkedHashMap<TenantAssetId, TenantAsset>();
        reverseAssets.put(HERO_ID, asset(HERO_ID, AssetKind.HERO));
        reverseAssets.put(LOGO_ID, asset(LOGO_ID, AssetKind.LOGO));
        when(repository.findActiveDefault(TENANT_ID)).thenReturn(Optional.of(stored));
        when(repository.findFeatures(TENANT_ID)).thenReturn(forwardFeatures, reverseFeatures);
        when(assetService.getActiveAssets(eq(TENANT_ID), org.mockito.ArgumentMatchers.anyCollection()))
                .thenReturn(forwardAssets, reverseAssets);

        var first = query.get(CONTEXT).entityTag();
        var second = query.get(CONTEXT).entityTag();

        assertThat(first).isEqualTo(second).matches("[0-9a-f]{64}");
    }

    @Test
    void etagChangesWithPublicValuesButNotWithPersistenceOnlyTimestamps() {
        var original = storedConfiguration(LOGO_ID, HERO_ID);
        var changedTitle = withHeroTitle(original, "Новый заголовок");
        var changedRevisionOnly = withRevision(original, REVISION.plusMinutes(5));
        var resolvedAssets = Map.of(
                LOGO_ID, asset(LOGO_ID, AssetKind.LOGO),
                HERO_ID, asset(HERO_ID, AssetKind.HERO));
        when(repository.findActiveDefault(TENANT_ID)).thenReturn(
                Optional.of(original), Optional.of(changedTitle), Optional.of(changedRevisionOnly));
        when(repository.findFeatures(TENANT_ID)).thenReturn(features());
        when(assetService.getActiveAssets(eq(TENANT_ID), org.mockito.ArgumentMatchers.anyCollection()))
                .thenReturn(resolvedAssets);

        var originalTag = query.get(CONTEXT).entityTag();
        var changedPublicValueTag = query.get(CONTEXT).entityTag();
        var changedRevisionOnlyTag = query.get(CONTEXT).entityTag();

        assertThat(changedPublicValueTag).isNotEqualTo(originalTag);
        assertThat(changedRevisionOnlyTag).isEqualTo(originalTag);
    }

    @Test
    void etagIncludesResolvedPublicAssetUrls() {
        var stored = storedConfiguration(LOGO_ID, null);
        when(repository.findActiveDefault(TENANT_ID)).thenReturn(Optional.of(stored));
        when(repository.findFeatures(TENANT_ID)).thenReturn(features());
        when(assetService.getActiveAssets(eq(TENANT_ID), org.mockito.ArgumentMatchers.anyCollection()))
                .thenReturn(
                        Map.of(LOGO_ID, assetAt(LOGO_ID, AssetKind.LOGO, "https://cdn-a.example.test/logo.png")),
                        Map.of(LOGO_ID, assetAt(LOGO_ID, AssetKind.LOGO, "https://cdn-b.example.test/logo.png")));

        var first = query.get(CONTEXT).entityTag();
        var second = query.get(CONTEXT).entityTag();

        assertThat(second).isNotEqualTo(first);
    }

    @Test
    void publicReadUsesOneRepeatableReadSnapshot() throws Exception {
        var annotation = PublicRestaurantQuery.class
                .getMethod("get", TenantContext.class)
                .getAnnotation(Transactional.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.readOnly()).isTrue();
        assertThat(annotation.isolation()).isEqualTo(Isolation.REPEATABLE_READ);
    }

    private static StoredConfiguration storedConfiguration(TenantAssetId logoId, TenantAssetId heroId) {
        return new StoredConfiguration(
                UUID.fromString("10000000-0000-4000-8000-000000000010"),
                "stambul",
                "ru-RU",
                "RUB",
                "Europe/Moscow",
                "У Ризвана",
                logoId,
                null,
                heroId,
                null,
                new PublicRestaurantConfiguration.Hero(
                        null, "Hero", null, "Вкусно", null, "Меню", List.of()),
                new PublicRestaurantConfiguration.DesignTokens(
                        "#111111", "#222222", "#333333", "#444444", "#555555",
                        "#666666", "#777777", "1rem", "Inter"),
                List.of(new PublicRestaurantConfiguration.LocalizedText(
                        "ru-RU", "У Ризвана", "Ризван", "Ресторан", "Кухня", "Вкусно", "Intro",
                        "Меню", "Корзина", "Получение")),
                new PublicRestaurantConfiguration.SeoMetadata(
                        "У Ризвана", "Описание", URI.create("https://stambul.example.test/"), null),
                new PublicRestaurantConfiguration.PublicProfile(
                        "+79990000000", "Позвонить", null, "Ежедневно", null, List.of()),
                REVISION, REVISION, REVISION, REVISION);
    }

    private static StoredConfiguration withHeroTitle(StoredConfiguration source, String title) {
        var hero = source.hero();
        return new StoredConfiguration(
                source.restaurantId(), source.slug(), source.locale(), source.currency(), source.timezone(),
                source.displayName(), source.logoAssetId(), source.faviconAssetId(), source.heroAssetId(),
                source.openGraphAssetId(),
                new PublicRestaurantConfiguration.Hero(
                        hero.imageUrl(), hero.imageAlt(), hero.eyebrow(), title, hero.subtitle(),
                        hero.ctaLabel(), hero.benefits()),
                source.designTokens(), source.localizedTexts(), source.seoWithoutImage(), source.profile(),
                source.tenantUpdatedAt(), source.restaurantUpdatedAt(), source.brandingUpdatedAt(),
                source.profileUpdatedAt());
    }

    private static StoredConfiguration withRevision(StoredConfiguration source, OffsetDateTime revision) {
        return new StoredConfiguration(
                source.restaurantId(), source.slug(), source.locale(), source.currency(), source.timezone(),
                source.displayName(), source.logoAssetId(), source.faviconAssetId(), source.heroAssetId(),
                source.openGraphAssetId(), source.hero(), source.designTokens(), source.localizedTexts(),
                source.seoWithoutImage(), source.profile(), revision, revision, revision, revision);
    }

    private static List<StoredFeature> features() {
        return List.of(
                feature("orders", true, null, false, limits()),
                feature("contacts", true, null, false, null),
                feature("preorder", true, "Предзаказ", true, null),
                feature("delivery", true, "Доставка", false, null),
                feature("pickup", true, "Самовывоз", false, null),
                feature("delivery_blocks", false, null, false, null),
                feature("future_feature", true, null, false, null));
    }

    private static StoredFeature feature(
            String key,
            boolean enabled,
            String label,
            boolean isDefault,
            PublicRestaurantConfiguration.Limits limits) {
        return new StoredFeature(key, enabled, label, isDefault, limits, REVISION);
    }

    private static PublicRestaurantConfiguration.Limits limits() {
        return new PublicRestaurantConfiguration.Limits(99, 200, 80, 32, 240, 500, null, null, 15);
    }

    private static TenantAsset asset(TenantAssetId id, AssetKind kind) {
        return assetAt(id, kind, "https://assets.example.test/" + kind.storageName() + ".png");
    }

    private static TenantAsset assetAt(TenantAssetId id, AssetKind kind, String publicUrl) {
        return new TenantAsset(
                id,
                TENANT_ID,
                kind,
                "image/png",
                10,
                kind == AssetKind.LOGO ? "a".repeat(64) : "b".repeat(64),
                URI.create(publicUrl));
    }

    private static TenantAssetId assetId(String value) {
        return new TenantAssetId(UUID.fromString(value));
    }
}
