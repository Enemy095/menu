package com.salamu.menu.site.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.salamu.menu.asset.api.AssetCatalog;
import com.salamu.menu.asset.api.AssetId;
import com.salamu.menu.asset.api.AssetKind;
import com.salamu.menu.asset.api.PublicAsset;
import com.salamu.menu.restaurant.api.DefaultRestaurantQuery;
import com.salamu.menu.restaurant.api.DefaultRestaurantUnavailableException;
import com.salamu.menu.restaurant.api.RestaurantId;
import com.salamu.menu.restaurant.api.RestaurantSnapshot;
import com.salamu.menu.restaurant.api.RestaurantStatus;
import com.salamu.menu.site.api.DeliveryFeePolicy;
import com.salamu.menu.site.api.AvailableOrderType;
import com.salamu.menu.site.api.OrderType;
import com.salamu.menu.site.api.SiteNotReadyException;
import com.salamu.menu.site.api.WeeklyScheduleDay;
import com.salamu.menu.site.internal.application.SiteConfigurationRepository.StoredFeature;
import com.salamu.menu.site.internal.application.SiteConfigurationRepository.StoredSiteConfiguration;
import com.salamu.menu.site.internal.application.SiteConfigurationRepository.StoredSiteData;

class SiteConfigurationQueryServiceTests {

    private static final UUID RESTAURANT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID LOGO_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID FAVICON_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID HERO_ID = UUID.fromString("20000000-0000-0000-0000-000000000003");
    private static final UUID OPEN_GRAPH_ID = UUID.fromString("20000000-0000-0000-0000-000000000004");

    private SiteConfigurationRepository repository;
    private DefaultRestaurantQuery restaurantQuery;
    private AssetCatalog assetCatalog;
    private SiteConfigurationQueryService query;

    @BeforeEach
    void setUp() {
        repository = mock(SiteConfigurationRepository.class);
        restaurantQuery = mock(DefaultRestaurantQuery.class);
        assetCatalog = mock(AssetCatalog.class);
        var objectMapper = new ObjectMapper();
        query = new SiteConfigurationQueryService(
                repository,
                restaurantQuery,
                assetCatalog,
                objectMapper,
                new SiteRepresentationHasher(objectMapper));
        when(restaurantQuery.requireActiveDefault()).thenReturn(new RestaurantSnapshot(
                new RestaurantId(RESTAURANT_ID), "main", "Main restaurant", RestaurantStatus.ACTIVE));
        when(assetCatalog.requireActive(any(Collection.class))).thenReturn(assets());
    }

    @Test
    void buildsTheCompleteTypedConfigurationUsingOneAssetBatch() {
        when(repository.load()).thenReturn(Optional.of(data(defaultWorkingHours(), features(defaultLimits(), false))));

        var result = query.current();

        assertThat(result.restaurantId()).isEqualTo(new RestaurantId(RESTAURANT_ID));
        assertThat(result.branding().logoUrl()).hasToString(assetUrl(AssetKind.LOGO, "a", "png").toString());
        assertThat(result.branding().faviconUrl()).isNotNull();
        assertThat(result.branding().hero().imageUrl()).isNotNull();
        assertThat(result.seo().openGraphImageUrl()).isNotNull();
        assertThat(result.profile().publicEmail()).isEqualTo("public@example.test");
        assertThat(result.schedule().days()).hasSize(7);
        assertThat(result.schedule().days()).extracting(WeeklyScheduleDay::dayOfWeek)
                .containsExactly(DayOfWeek.values());
        assertThat(result.schedule().days().getFirst().intervals()).hasSize(2);
        assertThat(result.schedule().days().get(1).intervals()).isEmpty();
        assertThat(result.orderTypes()).extracting(AvailableOrderType::code)
                .containsExactly(OrderType.PREORDER, OrderType.DELIVERY, OrderType.PICKUP);
        assertThat(result.orderTypes().stream()
                .filter(AvailableOrderType::isDefault)
                .map(AvailableOrderType::code)
                .toList()).containsExactly(OrderType.PREORDER);
        assertThat(result.deliveryConfiguration().enabled()).isTrue();
        assertThat(result.deliveryConfiguration().addressRequired()).isTrue();
        assertThat(result.deliveryConfiguration().feePolicy()).isEqualTo(DeliveryFeePolicy.NONE);
        assertThat(result.representationHash()).matches("[0-9a-f]{64}");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<AssetId>> ids = ArgumentCaptor.forClass(Collection.class);
        verify(assetCatalog).requireActive(ids.capture());
        assertThat(ids.getValue()).containsExactlyInAnyOrder(
                new AssetId(LOGO_ID),
                new AssetId(FAVICON_ID),
                new AssetId(HERO_ID),
                new AssetId(OPEN_GRAPH_ID));
        verify(repository).load();
        verify(restaurantQuery).requireActiveDefault();
    }

    @Test
    void representationHashIncludesScheduleAndIsDeterministic() {
        var first = data(defaultWorkingHours(), features(defaultLimits(), false));
        var changed = data(
                "[{\"day\":\"monday\",\"opensAt\":\"11:00\",\"closesAt\":\"12:00\"}]",
                features(defaultLimits(), false));
        when(repository.load()).thenReturn(Optional.of(first), Optional.of(first), Optional.of(changed));

        String firstHash = query.current().representationHash();
        assertThat(query.current().representationHash()).isEqualTo(firstHash);
        assertThat(query.current().representationHash()).isNotEqualTo(firstHash);
    }

    @Test
    void scheduleRejectsUnknownDuplicateOverlappingReversedAndMoreThanEightIntervals() {
        var invalidSchedules = new ArrayList<String>();
        invalidSchedules.add("[{\"day\":\"holiday\",\"opensAt\":\"10:00\",\"closesAt\":\"11:00\"}]");
        invalidSchedules.add("""
                [
                {"day":"monday","opensAt":"10:00","closesAt":"11:00"},
                {"day":"monday","opensAt":"10:00","closesAt":"11:00"}
                ]""");
        invalidSchedules.add("""
                [
                {"day":"monday","opensAt":"10:00","closesAt":"12:00"},
                {"day":"monday","opensAt":"11:00","closesAt":"13:00"}
                ]""");
        invalidSchedules.add("[{\"day\":\"monday\",\"opensAt\":\"12:00\",\"closesAt\":\"10:00\"}]");
        invalidSchedules.add(nineIntervals());

        for (String workingHours : invalidSchedules) {
            when(repository.load()).thenReturn(Optional.of(data(
                    workingHours, features(defaultLimits(), false))));
            assertNotReady(query::current);
        }
        verifyNoInteractions(assetCatalog);
    }

    @Test
    void invalidBoundsAndOrderTypeDefaultsFailClosedWhileUnknownFeaturesAreIgnored() {
        when(repository.load()).thenReturn(Optional.of(data(
                defaultWorkingHours(),
                features(defaultLimits().replace("\"maxItemQuantity\":99", "\"maxItemQuantity\":100"), false))));
        assertNotReady(query::current);

        when(repository.load()).thenReturn(Optional.of(data(
                defaultWorkingHours(), features(defaultLimits(), true))));
        assertNotReady(query::current);

        var withUnknown = new ArrayList<>(features(defaultLimits(), false));
        withUnknown.add(new StoredFeature("future_capability", true, "{\"unrecognizedShape\":[1,2,3]}"));
        when(repository.load()).thenReturn(Optional.of(data(defaultWorkingHours(), withUnknown)));
        assertThat(query.current().features().orders()).isTrue();
    }

    @Test
    void missingOrWrongKindAssetsMapToTheSameRedactedSiteNotReadySignal() {
        when(repository.load()).thenReturn(Optional.of(data(defaultWorkingHours(), features(defaultLimits(), false))));
        var wrong = new java.util.HashMap<>(assets());
        wrong.put(new AssetId(LOGO_ID), asset(LOGO_ID, AssetKind.HERO, "a", "png"));
        when(assetCatalog.requireActive(any(Collection.class))).thenReturn(Map.copyOf(wrong));

        assertNotReady(query::current);
    }

    @Test
    void absentSiteFailsBeforeRestaurantAndAssetLookup() {
        when(repository.load()).thenReturn(Optional.empty());
        assertNotReady(query::current);
        verifyNoInteractions(restaurantQuery);
        verifyNoInteractions(assetCatalog);
    }

    @Test
    void unavailableDefaultRestaurantMapsToSiteNotReadyBeforeAssetLookup() {
        when(repository.load()).thenReturn(Optional.of(data(defaultWorkingHours(), features(defaultLimits(), false))));
        when(restaurantQuery.requireActiveDefault()).thenThrow(new DefaultRestaurantUnavailableException());

        assertNotReady(query::current);

        verifyNoInteractions(assetCatalog);
    }

    private static StoredSiteData data(String workingHours, List<StoredFeature> features) {
        return new StoredSiteData(configuration(workingHours), features);
    }

    private static StoredSiteConfiguration configuration(String workingHours) {
        return new StoredSiteConfiguration(
                (short) 1,
                "active",
                "ru-RU",
                "RUB",
                "Europe/Moscow",
                "Restaurant",
                "restaurant",
                LOGO_ID,
                FAVICON_ID,
                """
                {
                  "primaryColor":"#112233","secondaryColor":"#223344","accentColor":"#334455",
                  "backgroundColor":"#ffffff","surfaceColor":"#eeeeee","textColor":"#111111",
                  "mutedTextColor":"#777777","borderRadius":"1rem","fontFamily":"Inter"
                }""",
                """
                [{
                  "locale":"ru-RU","displayName":"Ресторан","shortName":"Ресторан",
                  "businessTypeLabel":"Ресторан","cuisineText":"Кухня","slogan":"Вкусно",
                  "introText":"Добро пожаловать","menuSectionTitle":"Меню","cartTitle":"Корзина",
                  "orderTypeLegend":"Способ получения"
                }]""",
                """
                {
                  "imageAssetId":"%s","imageAlt":"Интерьер","eyebrow":"Добро пожаловать",
                  "title":"Ресторан","subtitle":"Каждый день","ctaLabel":"Открыть меню",
                  "benefits":[{"icon":"★","text":"Свежие продукты"}]
                }""".formatted(HERO_ID),
                """
                {
                  "title":"Ресторан","description":"Описание","canonicalUrl":"https://example.test/",
                  "openGraphAssetId":"%s"
                }""".formatted(OPEN_GRAPH_ID),
                "+7 999 000-00-00",
                "Позвонить",
                "public@example.test",
                "Москва",
                workingHours,
                "Ежедневно",
                new java.math.BigDecimal("55.750000"),
                new java.math.BigDecimal("37.620000"),
                "[{\"type\":\"telegram\",\"label\":\"Telegram\",\"url\":\"https://t.me/example\"}]");
    }

    private static List<StoredFeature> features(String limits, boolean twoDefaults) {
        return List.of(
                new StoredFeature("orders", true, "{\"limits\":" + limits + "}"),
                new StoredFeature("contacts", true, "{}"),
                new StoredFeature("geo_location", true, "{}"),
                new StoredFeature("social_links", true, "{}"),
                new StoredFeature("preorder", true, "{\"label\":\"Предзаказ\",\"default\":true}"),
                new StoredFeature("delivery", true,
                        "{\"label\":\"Доставка\",\"default\":" + twoDefaults + "}"),
                new StoredFeature("pickup", true, "{\"label\":\"Самовывоз\",\"default\":false}"),
                new StoredFeature("custom_theme", true, "{}"),
                new StoredFeature("whatsapp_fallback", false, "{}"),
                new StoredFeature("promotions", false, "{}"),
                new StoredFeature("loyalty", false, "{}"),
                new StoredFeature("reservations", false, "{}"),
                new StoredFeature("delivery_blocks", false, "{}"));
    }

    private static String defaultLimits() {
        return """
        {
          "maxItemQuantity":99,"maxTotalItems":200,"maxNameLength":80,"maxPhoneLength":32,
          "maxAddressLength":240,"maxCommentLength":500,"delayedOrderConfirmationMinutes":15,
          "minOrderMinor":0,"freeDeliveryThresholdMinor":10000
        }""";
    }

    private static String defaultWorkingHours() {
        return """
        [
          {"day":"monday","opensAt":"13:00","closesAt":"14:00"},
          {"day":"monday","opensAt":"10:00","closesAt":"12:00"},
          {"day":"sunday","opensAt":"11:00","closesAt":"20:00"}
        ]""";
    }

    private static String nineIntervals() {
        var values = new ArrayList<String>();
        for (int hour = 0; hour < 9; hour++) {
            values.add("{\"day\":\"monday\",\"opensAt\":\"%02d:00\",\"closesAt\":\"%02d:30\"}"
                    .formatted(hour, hour));
        }
        return "[" + String.join(",", values) + "]";
    }

    private static Map<AssetId, PublicAsset> assets() {
        return Map.of(
                new AssetId(LOGO_ID), asset(LOGO_ID, AssetKind.LOGO, "a", "png"),
                new AssetId(FAVICON_ID), asset(FAVICON_ID, AssetKind.FAVICON, "b", "ico"),
                new AssetId(HERO_ID), asset(HERO_ID, AssetKind.HERO, "c", "webp"),
                new AssetId(OPEN_GRAPH_ID), asset(OPEN_GRAPH_ID, AssetKind.OPEN_GRAPH, "d", "jpg"));
    }

    private static PublicAsset asset(UUID id, AssetKind kind, String hashCharacter, String extension) {
        String sha = hashCharacter.repeat(64);
        return new PublicAsset(new AssetId(id), kind, assetUrl(kind, hashCharacter, extension), sha);
    }

    private static URI assetUrl(AssetKind kind, String hashCharacter, String extension) {
        String storageKind = switch (kind) {
            case LOGO -> "logo";
            case FAVICON -> "favicon";
            case HERO -> "hero";
            case OPEN_GRAPH -> "open_graph";
            case CATEGORY -> "menu_category_image";
            case MENU_ITEM -> "menu_item_image";
        };
        return URI.create("https://cdn.example.test/assets/" + storageKind + "/"
                + hashCharacter.repeat(64) + "." + extension);
    }

    private static void assertNotReady(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(SiteNotReadyException.class)
                .hasMessage("Public site configuration is not ready")
                .hasMessageNotContaining(LOGO_ID.toString())
                .hasMessageNotContaining("site_")
                .hasNoCause();
    }
}
