package com.salamu.menu.menu.internal.web;

import com.salamu.menu.menu.api.PublishedMenuQuery;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class PublicMenuController {

    static final String CACHE_CONTROL = "public, max-age=30, s-maxage=300, stale-while-revalidate=60";
    static final String CACHE_TAG_HEADER = "Cache-Tag";
    static final String SURROGATE_KEY_HEADER = "Surrogate-Key";

    private final PublishedMenuQuery publishedMenuQuery;

    PublicMenuController(PublishedMenuQuery publishedMenuQuery) {
        this.publishedMenuQuery = publishedMenuQuery;
    }

    @GetMapping(path = "/api/v1/public/menu", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<PublicMenuResponse> getPublicMenu() {
        var menu = publishedMenuQuery.current();
        var response = PublicMenuResponse.from(menu);
        var headers = new HttpHeaders();
        headers.setETag("\"menu-v" + menu.version() + "-" + menu.representationHash() + "\"");
        headers.set(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL);
        headers.set(CACHE_TAG_HEADER, "menu");
        headers.set(SURROGATE_KEY_HEADER, "menu menu-v" + menu.version());
        return ResponseEntity.ok().headers(headers).body(response);
    }
}
