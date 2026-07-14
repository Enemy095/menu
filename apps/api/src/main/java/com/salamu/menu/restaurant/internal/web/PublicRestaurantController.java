package com.salamu.menu.restaurant.internal.web;

import jakarta.servlet.http.HttpServletRequest;

import com.salamu.menu.restaurant.internal.application.PublicRestaurantQuery;
import com.salamu.menu.tenancy.api.TenantContext;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class PublicRestaurantController {

    private static final String CACHE_CONTROL = "public, max-age=60, s-maxage=300, stale-while-revalidate=60";
    private static final String CACHE_TAG = "Cache-Tag";
    private static final String SURROGATE_KEY = "Surrogate-Key";

    private final PublicRestaurantQuery query;
    private final RestaurantResponseMapper mapper;

    PublicRestaurantController(PublicRestaurantQuery query, RestaurantResponseMapper mapper) {
        this.query = query;
        this.mapper = mapper;
    }

    @GetMapping("/api/v1/public/{tenantSlug}/restaurant")
    ResponseEntity<?> getRestaurant(
            TenantContext tenantContext,
            HttpServletRequest request) {
        var configuration = query.get(tenantContext);
        var entityTag = quote(configuration.entityTag());
        var notModified = matches(request, entityTag);
        var response = ResponseEntity.status(notModified ? HttpStatus.NOT_MODIFIED : HttpStatus.OK)
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                .header(CACHE_TAG, "tenant-" + tenantContext.slug())
                .header(SURROGATE_KEY, "tenant-" + tenantContext.slug() + " restaurant-" + tenantContext.slug())
                .header(HttpHeaders.ETAG, entityTag);
        if (notModified) {
            return response.build();
        }
        return response.body(mapper.map(configuration));
    }

    private static boolean matches(HttpServletRequest request, String currentTag) {
        for (var values = request.getHeaders(HttpHeaders.IF_NONE_MATCH);
                values != null && values.hasMoreElements(); ) {
            var header = values.nextElement();
            for (var candidate : header.split(",")) {
                var normalized = candidate.trim();
                if ("*".equals(normalized)) {
                    return true;
                }
                if (normalized.startsWith("W/")) {
                    normalized = normalized.substring(2).trim();
                }
                if (currentTag.equals(normalized)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String quote(String value) {
        return '"' + value + '"';
    }
}
