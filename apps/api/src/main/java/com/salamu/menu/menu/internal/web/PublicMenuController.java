package com.salamu.menu.menu.internal.web;

import com.salamu.menu.menu.internal.application.PublicMenuQueryService;
import com.salamu.menu.tenancy.api.TenantContext;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
class PublicMenuController {

    static final String CACHE_CONTROL = "public, max-age=30, s-maxage=300, stale-while-revalidate=60";
    static final String CACHE_TAG_HEADER = "Cache-Tag";
    static final String SURROGATE_KEY_HEADER = "Surrogate-Key";

    private final PublicMenuQueryService queryService;

    PublicMenuController(PublicMenuQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping(
            path = "/api/v1/public/{tenantSlug}/menu",
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<PublicMenuResponse> getPublicMenu(
            TenantContext tenantContext,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        var menu = queryService.getCurrentPublished(tenantContext);
        var response = PublicMenuResponse.from(menu);
        var etag = PublicMenuRepresentationTag.etag(tenantContext.slug(), response);
        var headers = responseHeaders(tenantContext.slug(), etag);
        if (matches(ifNoneMatch, etag)) {
            return new ResponseEntity<>(null, headers, HttpStatus.NOT_MODIFIED);
        }
        return new ResponseEntity<>(response, headers, HttpStatus.OK);
    }

    static boolean matches(String ifNoneMatch, String etag) {
        if (ifNoneMatch == null || ifNoneMatch.isBlank()) {
            return false;
        }
        for (String candidate : ifNoneMatch.split(",")) {
            var normalized = candidate.trim();
            if (normalized.startsWith("W/")) {
                normalized = normalized.substring(2).trim();
            }
            if ("*".equals(normalized) || etag.equals(normalized)) {
                return true;
            }
        }
        return false;
    }

    private static HttpHeaders responseHeaders(String tenantSlug, String etag) {
        var headers = new HttpHeaders();
        headers.setETag(etag);
        headers.set(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL);
        var tenantTag = "tenant-" + tenantSlug;
        headers.set(CACHE_TAG_HEADER, tenantTag);
        headers.set(SURROGATE_KEY_HEADER, tenantTag);
        return headers;
    }
}
