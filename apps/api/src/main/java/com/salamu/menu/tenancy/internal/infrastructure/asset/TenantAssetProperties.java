package com.salamu.menu.tenancy.internal.infrastructure.asset;

import java.net.URI;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app.assets")
public record TenantAssetProperties(
        @NotNull @Valid Storage storage,
        @NotNull URI cdnBaseUrl) {

    public TenantAssetProperties {
        requireHttpUrl(cdnBaseUrl, "cdnBaseUrl");
    }

    public record Storage(
            @NotNull URI endpoint,
            @NotBlank String region,
            @NotBlank @Pattern(regexp = "^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$") String bucket,
            @NotBlank String accessKey,
            @NotBlank String secretKey,
            boolean pathStyleAccess) {

        public Storage {
            requireHttpUrl(endpoint, "storage.endpoint");
        }

        @Override
        public String toString() {
            return "Storage[endpoint=%s, region=%s, bucket=%s, accessKey=<redacted>, secretKey=<redacted>, "
                    .formatted(endpoint, region, bucket)
                    + "pathStyleAccess=" + pathStyleAccess + "]";
        }
    }

    private static void requireHttpUrl(URI uri, String property) {
        if (uri == null) {
            return;
        }
        boolean http = "http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme());
        if (!uri.isAbsolute()
                || !http
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null) {
            throw new IllegalArgumentException(property + " must be an absolute HTTP(S) URL without credentials, query or fragment");
        }
    }
}
