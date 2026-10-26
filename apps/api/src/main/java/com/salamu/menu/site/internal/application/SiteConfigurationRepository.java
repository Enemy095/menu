package com.salamu.menu.site.internal.application;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SiteConfigurationRepository {

    Optional<StoredSiteData> load();

    record StoredSiteData(StoredSiteConfiguration configuration, List<StoredFeature> features) {
        public StoredSiteData {
            features = List.copyOf(features);
        }
    }

    record StoredSiteConfiguration(
            short singletonId,
            String status,
            String locale,
            String currency,
            String timezone,
            String displayName,
            String businessType,
            String logoAlt,
            UUID logoAssetId,
            UUID faviconAssetId,
            String designTokensJson,
            String localizedTextsJson,
            String heroJson,
            String seoJson,
            String publicPhone,
            String publicPhoneLabel,
            String publicEmail,
            String address,
            String workingHoursJson,
            String workingHoursText,
            BigDecimal latitude,
            BigDecimal longitude,
            String socialLinksJson) {
    }

    record StoredFeature(String key, boolean enabled, String configJson) {
    }
}
