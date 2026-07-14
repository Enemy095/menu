package com.salamu.menu.menu.internal.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;

import com.salamu.menu.menu.internal.application.PublicMenuQueryService;
import com.salamu.menu.menu.internal.application.PublishedMenu;
import com.salamu.menu.tenancy.api.TenantContext;
import com.salamu.menu.tenancy.api.TenantId;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;

class PublicMenuControllerTests {

    private static final TenantContext CONTEXT = new TenantContext(
            new TenantId(UUID.fromString("10000000-0000-4000-8000-000000000001")),
            "stambul");

    @Test
    void emitsExactContractShapeAndOmitsAbsentOptionalFields() throws Exception {
        var queryService = mock(PublicMenuQueryService.class);
        when(queryService.getCurrentPublished(CONTEXT)).thenReturn(menu());
        var response = new PublicMenuController(queryService).getPublicMenu(CONTEXT, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getETag())
                .matches("\\\"menu-stambul-v7-[0-9a-f]{64}\\\"");
        assertThat(response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL))
                .isEqualTo(PublicMenuController.CACHE_CONTROL);
        assertThat(response.getHeaders().getFirst(PublicMenuController.CACHE_TAG_HEADER))
                .isEqualTo("tenant-stambul");
        assertThat(response.getHeaders().getFirst(PublicMenuController.SURROGATE_KEY_HEADER))
                .isEqualTo("tenant-stambul");

        var json = new ObjectMapper().writeValueAsString(response.getBody());
        @SuppressWarnings("unchecked")
        var root = (java.util.Map<String, Object>) JsonPath.read(json, "$");
        assertThat(root.keySet()).containsExactlyInAnyOrder(
                "restaurantId", "version", "publishedAt", "currency", "categories", "items");
        @SuppressWarnings("unchecked")
        var category = (java.util.Map<String, Object>) JsonPath.read(json, "$.categories[0]");
        assertThat(category.keySet()).containsExactlyInAnyOrder("categoryId", "name", "sortOrder");
        @SuppressWarnings("unchecked")
        var item = (java.util.Map<String, Object>) JsonPath.read(json, "$.items[0]");
        assertThat(item.keySet()).containsExactlyInAnyOrder(
                "menuItemId", "categoryId", "name", "priceMinor", "available", "sortOrder");
        assertThat(json).doesNotContain("tenantId", "objectKey", "description\":null", "imageUrl\":null");
    }

    @Test
    void exactWeakOrListedIfNoneMatchReturns304WithCacheHeadersAndNoBody() {
        var queryService = mock(PublicMenuQueryService.class);
        when(queryService.getCurrentPublished(CONTEXT)).thenReturn(menu());
        var controller = new PublicMenuController(queryService);
        var etag = controller.getPublicMenu(CONTEXT, null).getHeaders().getETag();

        var response = controller.getPublicMenu(
                CONTEXT,
                "\"another\", W/" + etag);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
        assertThat(response.getBody()).isNull();
        assertThat(response.getHeaders().getETag()).isEqualTo(etag);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL))
                .isEqualTo(PublicMenuController.CACHE_CONTROL);
        assertThat(response.getHeaders().getFirst(PublicMenuController.CACHE_TAG_HEADER))
                .isEqualTo("tenant-stambul");
        assertThat(response.getHeaders().getFirst(PublicMenuController.SURROGATE_KEY_HEADER))
                .isEqualTo("tenant-stambul");
    }

    private static PublishedMenu menu() {
        return new PublishedMenu(
                UUID.fromString("11000000-0000-4000-8000-000000000001"),
                7,
                Instant.parse("2026-07-14T09:00:00Z"),
                "RUB",
                List.of(new PublishedMenu.Category("pizza", "Pizza", null, null, 10)),
                List.of(new PublishedMenu.Item(
                        "pizza-pepperoni",
                        "pizza",
                        "Pepperoni",
                        null,
                        59_000,
                        null,
                        true,
                        10)));
    }
}
