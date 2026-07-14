package com.salamu.menu.site.internal.web;

import com.salamu.menu.site.api.SiteConfigurationQuery;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class PublicSiteConfigurationController {

    static final String CACHE_CONTROL = "public, max-age=60, s-maxage=300, stale-while-revalidate=60";
    static final String CACHE_TAG = "site-config";

    private final SiteConfigurationQuery query;
    private final PublicConfigurationResponseMapper mapper;

    PublicSiteConfigurationController(
            SiteConfigurationQuery query,
            PublicConfigurationResponseMapper mapper) {
        this.query = query;
        this.mapper = mapper;
    }

    @GetMapping(path = "/api/v1/public/config", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<PublicConfigurationResponse> getConfiguration() {
        var configuration = query.current();
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, '"' + "config-" + configuration.representationHash() + '"')
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                .header("Cache-Tag", CACHE_TAG)
                .header("Surrogate-Key", CACHE_TAG)
                .header(HttpHeaders.VARY, HttpHeaders.ACCEPT_ENCODING)
                .body(mapper.map(configuration));
    }
}
