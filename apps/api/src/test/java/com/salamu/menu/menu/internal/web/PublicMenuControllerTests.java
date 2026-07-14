package com.salamu.menu.menu.internal.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;

import com.salamu.menu.menu.api.MenuItemId;
import com.salamu.menu.menu.api.MenuVersionId;
import com.salamu.menu.menu.api.PublishedMenuQuery;
import com.salamu.menu.menu.api.PublishedMenuSnapshot;
import com.salamu.menu.restaurant.api.RestaurantId;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import tools.jackson.databind.ObjectMapper;

class PublicMenuControllerTests {

    @Test
    void emitsExactContractShapeNeutralCacheMetadataAndNoConditionalInput() throws Exception {
        var query = mock(PublishedMenuQuery.class);
        when(query.current()).thenReturn(menu());

        var response = new PublicMenuController(query).getPublicMenu();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getETag())
                .isEqualTo("\"menu-v7-" + "a".repeat(64) + "\"");
        assertThat(response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL))
                .isEqualTo(PublicMenuController.CACHE_CONTROL);
        assertThat(response.getHeaders().getFirst(PublicMenuController.CACHE_TAG_HEADER))
                .isEqualTo("menu");
        assertThat(response.getHeaders().getFirst(PublicMenuController.SURROGATE_KEY_HEADER))
                .isEqualTo("menu menu-v7");

        String json = new ObjectMapper().writeValueAsString(response.getBody());
        @SuppressWarnings("unchecked")
        Map<String, Object> root = (Map<String, Object>) JsonPath.read(json, "$");
        assertThat(root.keySet()).containsExactlyInAnyOrder(
                "restaurantId", "version", "publishedAt", "currency", "categories", "items");
        @SuppressWarnings("unchecked")
        Map<String, Object> item = (Map<String, Object>) JsonPath.read(json, "$.items[0]");
        assertThat(item.keySet()).containsExactlyInAnyOrder(
                "menuItemId",
                "categoryId",
                "name",
                "priceMinor",
                "available",
                "sortOrder",
                "modifierGroups");
        @SuppressWarnings("unchecked")
        Map<String, Object> group = (Map<String, Object>) JsonPath.read(json, "$.items[0].modifierGroups[0]");
        assertThat(group.keySet()).containsExactlyInAnyOrder(
                "modifierGroupId", "name", "minSelections", "maxSelections", "sortOrder", "options");
        @SuppressWarnings("unchecked")
        Map<String, Object> option =
                (Map<String, Object>) JsonPath.read(json, "$.items[0].modifierGroups[0].options[0]");
        assertThat(option.keySet()).containsExactlyInAnyOrder(
                "optionId", "name", "priceDeltaMinor", "available", "sortOrder");
        assertThat(json).doesNotContain(
                "tenant", "objectKey", "description\":null", "imageUrl\":null", ITEM_ID.toString());
    }

    @Test
    void routeIsCanonicalAndControllerCannotReturn304() throws NoSuchMethodException {
        var method = PublicMenuController.class.getDeclaredMethod("getPublicMenu");
        var mapping = method.getAnnotation(GetMapping.class);

        assertThat(mapping.path()).containsExactly("/api/v1/public/menu");
        assertThat(method.getParameterCount()).isZero();
    }

    private static final MenuItemId ITEM_ID =
            new MenuItemId(UUID.fromString("30000000-0000-4000-8000-000000000003"));

    private static PublishedMenuSnapshot menu() {
        var option = new PublishedMenuSnapshot.ModifierOption("large", "Large", -100, false, 0);
        var group = new PublishedMenuSnapshot.ModifierGroup(
                "size", "Size", 0, 1, 0, List.of(option));
        var item = new PublishedMenuSnapshot.Item(
                ITEM_ID,
                "pepperoni",
                "pizza",
                "Pepperoni",
                null,
                59_000,
                "RUB",
                null,
                true,
                0,
                List.of(group));
        return new PublishedMenuSnapshot(
                new RestaurantId(UUID.fromString("10000000-0000-4000-8000-000000000001")),
                new MenuVersionId(UUID.fromString("20000000-0000-4000-8000-000000000002")),
                7,
                Instant.parse("2026-07-14T09:00:00Z"),
                "RUB",
                List.of(new PublishedMenuSnapshot.Category("pizza", "Pizza", null, null, 0)),
                List.of(item),
                "a".repeat(64));
    }
}
