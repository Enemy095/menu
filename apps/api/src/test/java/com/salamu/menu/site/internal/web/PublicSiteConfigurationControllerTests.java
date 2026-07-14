package com.salamu.menu.site.internal.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.net.URI;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;

import com.salamu.menu.restaurant.api.RestaurantId;
import com.salamu.menu.site.api.AvailableOrderType;
import com.salamu.menu.site.api.Branding;
import com.salamu.menu.site.api.DeliveryConfiguration;
import com.salamu.menu.site.api.DeliveryFeePolicy;
import com.salamu.menu.site.api.DesignTokens;
import com.salamu.menu.site.api.GeoLocation;
import com.salamu.menu.site.api.Hero;
import com.salamu.menu.site.api.HeroBenefit;
import com.salamu.menu.site.api.LocalizedSiteText;
import com.salamu.menu.site.api.OpeningInterval;
import com.salamu.menu.site.api.OrderLimits;
import com.salamu.menu.site.api.OrderType;
import com.salamu.menu.site.api.PublicProfile;
import com.salamu.menu.site.api.Seo;
import com.salamu.menu.site.api.SiteConfigurationQuery;
import com.salamu.menu.site.api.SiteConfigurationSnapshot;
import com.salamu.menu.site.api.SiteFeatures;
import com.salamu.menu.site.api.SiteNotReadyException;
import com.salamu.menu.site.api.SocialLink;
import com.salamu.menu.site.api.SocialLinkType;
import com.salamu.menu.site.api.WeeklySchedule;
import com.salamu.menu.site.api.WeeklyScheduleDay;

class PublicSiteConfigurationControllerTests {

    private static final String HASH = "a".repeat(64);

    private final SiteConfigurationQuery query = mock(SiteConfigurationQuery.class);
    private final PublicSiteConfigurationController controller = new PublicSiteConfigurationController(
            query, new PublicConfigurationResponseMapper());

    @Test
    void returnsTheCompleteAcceptedRepresentationAndExactCacheMetadata() throws Exception {
        when(query.current()).thenReturn(configuration());

        var response = controller.getConfiguration();
        var body = new ObjectMapper().readTree(new ObjectMapper().writeValueAsBytes(response.getBody()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst(HttpHeaders.ETAG)).isEqualTo("\"config-" + HASH + "\"");
        assertThat(response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL))
                .isEqualTo(PublicSiteConfigurationController.CACHE_CONTROL);
        assertThat(response.getHeaders().getFirst("Cache-Tag")).isEqualTo("site-config");
        assertThat(response.getHeaders().getFirst("Surrogate-Key")).isEqualTo("site-config");
        assertThat(response.getHeaders().getFirst(HttpHeaders.VARY)).isEqualTo(HttpHeaders.ACCEPT_ENCODING);

        assertThat(body.properties()).extracting(java.util.Map.Entry::getKey).containsExactlyInAnyOrder(
                "restaurantId", "branding", "seo", "profile", "features", "orderTypes", "limits",
                "schedule", "deliveryConfiguration", "locale", "currency", "timezone");
        assertThat(body.at("/branding/localizedTexts/0/cuisineText").asString()).isEmpty();
        assertThat(body.at("/profile/publicEmail").asString()).isEqualTo("public@example.test");
        assertThat(body.at("/schedule/days").size()).isEqualTo(7);
        assertThat(body.at("/schedule/days/0/dayOfWeek").asString()).isEqualTo("monday");
        assertThat(body.at("/schedule/days/0/intervals/0/opensAt").asString()).isEqualTo("10:00");
        assertThat(body.at("/schedule/days/6/dayOfWeek").asString()).isEqualTo("sunday");
        assertThat(body.at("/deliveryConfiguration/enabled").booleanValue()).isTrue();
        assertThat(body.at("/deliveryConfiguration/addressRequired").booleanValue()).isTrue();
        assertThat(body.at("/deliveryConfiguration/feePolicy").asString()).isEqualTo("none");
        assertThat(body.at("/orderTypes/0/code").asString()).isEqualTo("delivery");
        assertThat(body.at("/orderTypes/0/isDefault").booleanValue()).isTrue();
        assertThat(body.has("representationHash")).isFalse();
        assertThat(body.at("/limits").has("minOrderMinor")).isFalse();
        assertThat(body.at("/limits").has("freeDeliveryThresholdMinor")).isFalse();

        var endpoint = PublicSiteConfigurationController.class.getDeclaredMethod("getConfiguration");
        assertThat(endpoint.getParameterCount()).isZero();
        assertThat(endpoint.getAnnotation(GetMapping.class).path())
                .containsExactly("/api/v1/public/config");
    }

    @Test
    void letsTheSafeSiteNotReadySignalReachTheRootErrorHandler() {
        var failure = new SiteNotReadyException();
        when(query.current()).thenThrow(failure);

        assertThatThrownBy(controller::getConfiguration).isSameAs(failure);
    }

    private static SiteConfigurationSnapshot configuration() {
        URI logo = URI.create("https://cdn.example.test/assets/logo/" + "1".repeat(64) + ".png");
        URI heroImage = URI.create("https://cdn.example.test/assets/hero/" + "2".repeat(64) + ".webp");
        var branding = new Branding(
                logo,
                "Restaurant logo",
                null,
                new Hero(
                        heroImage,
                        "Interior",
                        "Welcome",
                        "Restaurant",
                        "Every day",
                        "Open menu",
                        List.of(new HeroBenefit("*", "Fresh food"))),
                new DesignTokens(
                        "#112233",
                        "#223344",
                        "#334455",
                        "#ffffff",
                        "#eeeeee",
                        "#111111",
                        "#777777",
                        "1rem",
                        "Inter"),
                List.of(new LocalizedSiteText(
                        "ru-RU",
                        "Restaurant",
                        "Restaurant",
                        "Restaurant",
                        "",
                        "",
                        "",
                        "Menu",
                        "Cart",
                        "Order type")));
        var profile = new PublicProfile(
                "+7 999 000-00-00",
                "Call",
                "public@example.test",
                "Moscow",
                new GeoLocation(new BigDecimal("55.75"), new BigDecimal("37.62"), "Moscow"),
                List.of(new SocialLink(
                        SocialLinkType.TELEGRAM,
                        "Telegram",
                        URI.create("https://t.me/example"))));
        var schedule = new WeeklySchedule(
                "Europe/Moscow",
                "Every day",
                Arrays.stream(DayOfWeek.values())
                        .map(day -> new WeeklyScheduleDay(
                                day,
                                day == DayOfWeek.MONDAY
                                        ? List.of(new OpeningInterval(LocalTime.of(10, 0), LocalTime.of(20, 0)))
                                        : List.of()))
                        .toList());
        return new SiteConfigurationSnapshot(
                new RestaurantId(UUID.fromString("10000000-0000-0000-0000-000000000001")),
                branding,
                new Seo("Restaurant", "Description", URI.create("https://example.test/"), null),
                profile,
                new SiteFeatures(true, true, true, true, true, false, false, true, false,
                        false, false, false, false),
                List.of(new AvailableOrderType(OrderType.DELIVERY, "Delivery", true)),
                new OrderLimits(10, 100, 80, 32, 240, 500, 15),
                schedule,
                new DeliveryConfiguration(true, true, DeliveryFeePolicy.NONE),
                "ru-RU",
                "RUB",
                "Europe/Moscow",
                HASH);
    }
}
