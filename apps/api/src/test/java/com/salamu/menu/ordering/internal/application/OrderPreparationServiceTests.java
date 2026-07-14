package com.salamu.menu.ordering.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.salamu.menu.menu.api.MenuItemId;
import com.salamu.menu.menu.api.MenuVersionId;
import com.salamu.menu.menu.api.PublishedMenuQuery;
import com.salamu.menu.menu.api.PublishedMenuSnapshot;
import com.salamu.menu.ordering.api.OrderApiException;
import com.salamu.menu.restaurant.api.RestaurantId;
import com.salamu.menu.site.api.AvailableOrderType;
import com.salamu.menu.site.api.OrderLimits;
import com.salamu.menu.site.api.SiteConfigurationQuery;
import com.salamu.menu.site.api.SiteConfigurationSnapshot;
import com.salamu.menu.site.api.SiteFeatures;

import org.junit.jupiter.api.Test;

class OrderPreparationServiceTests {

    private static final RestaurantId RESTAURANT = new RestaurantId(
            UUID.fromString("10000000-0000-4000-8000-000000000001"));
    private static final MenuVersionId MENU = new MenuVersionId(
            UUID.fromString("20000000-0000-4000-8000-000000000002"));

    @Test
    void validatesCurrentMenuAndCalculatesAuthoritativeSnapshotPrice() {
        var service = service(menu(true));
        var command = command(7, List.of(new CreateOrderCommand.Modifier("size", "large", 1)));

        var prepared = service.prepare(command);

        assertThat(prepared.totalMinor()).isEqualTo(138_000);
        assertThat(prepared.currency()).isEqualTo("RUB");
        assertThat(prepared.lines()).singleElement().satisfies(line -> {
            assertThat(line.unitPriceMinor()).isEqualTo(69_000);
            assertThat(line.lineTotalMinor()).isEqualTo(138_000);
            assertThat(line.itemName()).isEqualTo("Pepperoni");
            assertThat(line.modifiers()).singleElement().satisfies(modifier -> {
                assertThat(modifier.groupName()).isEqualTo("Size");
                assertThat(modifier.optionName()).isEqualTo("Large");
                assertThat(modifier.priceDeltaMinor()).isEqualTo(10_000);
            });
        });
    }

    @Test
    void rejectsStaleMenuUnavailableItemsAndMissingRequiredModifier() {
        var service = service(menu(true));
        assertCode(() -> service.prepare(command(6, List.of(
                new CreateOrderCommand.Modifier("size", "large", 1)))),
                OrderApiException.Code.MENU_CHANGED);

        var unavailableService = service(menu(false));
        assertCode(() -> unavailableService.prepare(command(7, List.of(
                new CreateOrderCommand.Modifier("size", "large", 1)))),
                OrderApiException.Code.ITEM_UNAVAILABLE);

        assertCode(() -> service.prepare(command(7, List.of())),
                OrderApiException.Code.VALIDATION_ERROR);
    }

    private static OrderPreparationService service(PublishedMenuSnapshot menu) {
        SiteConfigurationQuery siteQuery = mock(SiteConfigurationQuery.class);
        PublishedMenuQuery menuQuery = mock(PublishedMenuQuery.class);
        SiteConfigurationSnapshot site = mock(SiteConfigurationSnapshot.class);
        when(siteQuery.current()).thenReturn(site);
        when(menuQuery.current()).thenReturn(menu);
        when(site.restaurantId()).thenReturn(RESTAURANT);
        when(site.currency()).thenReturn("RUB");
        when(site.features()).thenReturn(new SiteFeatures(
                true, true, true, true, true, true, true, true,
                false, false, false, false, false));
        when(site.orderTypes()).thenReturn(List.of(
                new AvailableOrderType(com.salamu.menu.site.api.OrderType.PICKUP, "Pickup", true)));
        when(site.limits()).thenReturn(new OrderLimits(10, 20, 80, 32, 240, 500, 0));
        return new OrderPreparationService(siteQuery, menuQuery);
    }

    private static PublishedMenuSnapshot menu(boolean itemAvailable) {
        var category = new PublishedMenuSnapshot.Category("pizza", "Pizza", null, null, 0);
        var group = new PublishedMenuSnapshot.ModifierGroup(
                "size", "Size", 1, 1, 0,
                List.of(new PublishedMenuSnapshot.ModifierOption(
                        "large", "Large", 10_000, true, 0)));
        var item = new PublishedMenuSnapshot.Item(
                new MenuItemId(UUID.fromString("30000000-0000-4000-8000-000000000003")),
                "pepperoni", "pizza", "Pepperoni", null, 59_000, "RUB", null,
                itemAvailable, 0, List.of(group));
        return new PublishedMenuSnapshot(
                RESTAURANT, MENU, 7, Instant.parse("2026-07-14T09:00:00Z"), "RUB",
                List.of(category), List.of(item), "a".repeat(64));
    }

    private static CreateOrderCommand command(long menuVersion, List<CreateOrderCommand.Modifier> modifiers) {
        return new CreateOrderCommand(
                CreateOrderCommand.OrderType.PICKUP,
                new CreateOrderCommand.Customer("Customer", "+79990000000"),
                new CreateOrderCommand.Fulfillment(null, null),
                List.of(new CreateOrderCommand.Item("pepperoni", 2, modifiers)),
                null,
                menuVersion);
    }

    private static void assertCode(Runnable action, OrderApiException.Code code) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(OrderApiException.class,
                        exception -> assertThat(exception.code()).isEqualTo(code));
    }
}
