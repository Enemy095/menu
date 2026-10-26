package com.salamu.menu.asset.internal.infrastructure;

import java.net.URI;
import java.util.Objects;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.assets")
public record AssetProperties(
        @NotNull @Valid Storage storage,
        @NotNull URI cdnBaseUrl) {

    public AssetProperties {
        Objects.requireNonNull(storage, "storage");
        Objects.requireNonNull(cdnBaseUrl, "cdnBaseUrl");
        CdnAssetPublicUrlResolver.validateBaseUrl(cdnBaseUrl);
    }

    @Override
    public String toString() {
        return "AssetProperties[storage=%s, cdnBaseUrl=%s]".formatted(storage, cdnBaseUrl);
    }

    public record Storage(
            @NotNull URI endpoint,
            @NotBlank @Pattern(regexp = "^[a-z0-9][a-z0-9-]*[a-z0-9]$") String region,
            @NotBlank @Pattern(regexp = "^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$") String bucket,
            @NotBlank String accessKey,
            @NotBlank String secretKey,
            boolean pathStyleAccess) {

        public Storage {
            Objects.requireNonNull(endpoint, "endpoint");
            requireStorageEndpoint(endpoint);
        }

        @Override
        public String toString() {
            return "Storage[endpoint=%s, region=%s, bucket=%s, accessKey=<redacted>, "
                    .formatted(endpoint, region, bucket)
                    + "secretKey=<redacted>, pathStyleAccess=" + pathStyleAccess + "]";
        }

        private static void requireStorageEndpoint(URI endpoint) {
            String scheme = endpoint.getScheme();
            if (!endpoint.isAbsolute()
                    || !("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
                    || endpoint.getHost() == null
                    || endpoint.getUserInfo() != null
                    || endpoint.getQuery() != null
                    || endpoint.getFragment() != null
                    || !endpoint.normalize().equals(endpoint)) {
                throw new IllegalArgumentException(
                        "app.assets.storage.endpoint must be a safe absolute HTTP(S) URL");
            }
        }
    }
}
