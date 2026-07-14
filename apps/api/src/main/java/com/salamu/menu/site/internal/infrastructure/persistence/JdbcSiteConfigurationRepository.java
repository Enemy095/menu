package com.salamu.menu.site.internal.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.salamu.menu.site.internal.application.SiteConfigurationCorruptException;
import com.salamu.menu.site.internal.application.SiteConfigurationRepository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcSiteConfigurationRepository implements SiteConfigurationRepository {

    private static final String CONFIGURATION_QUERY = """
            SELECT s.singleton_id,
                   s.status,
                   s.locale,
                   s.currency,
                   s.timezone,
                   b.display_name,
                   b.business_type,
                   b.logo_asset_id,
                   b.favicon_asset_id,
                   b.design_tokens::text AS design_tokens,
                   b.localized_texts::text AS localized_texts,
                   b.hero::text AS hero,
                   b.seo_metadata::text AS seo_metadata,
                   p.public_phone,
                   p.public_phone_label,
                   p.public_email,
                   p.address_text,
                   p.working_hours::text AS working_hours,
                   p.working_hours_text,
                   p.latitude,
                   p.longitude,
                   p.social_links::text AS social_links,
                   f.feature_key,
                   f.enabled AS feature_enabled,
                   f.config::text AS feature_config
              FROM site_configuration s
              LEFT JOIN site_branding b ON b.singleton_id = s.singleton_id
              LEFT JOIN site_profile p ON p.singleton_id = s.singleton_id
              LEFT JOIN site_features f ON true
             ORDER BY s.singleton_id, f.feature_key
            """;

    private final JdbcClient jdbcClient;

    JdbcSiteConfigurationRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public Optional<StoredSiteData> load() {
        List<StoredRow> rows = jdbcClient.sql(CONFIGURATION_QUERY).query(this::mapRow).list();
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        StoredSiteConfiguration configuration = rows.getFirst().configuration();
        var features = new ArrayList<StoredFeature>();
        for (StoredRow row : rows) {
            if (!configuration.equals(row.configuration())) {
                throw new SiteConfigurationCorruptException();
            }
            if (row.feature() != null) {
                features.add(row.feature());
            }
        }
        return Optional.of(new StoredSiteData(configuration, features));
    }

    private StoredRow mapRow(ResultSet resultSet, int rowNumber) throws SQLException {
        var configuration = new StoredSiteConfiguration(
                resultSet.getShort("singleton_id"),
                resultSet.getString("status"),
                resultSet.getString("locale"),
                resultSet.getString("currency"),
                resultSet.getString("timezone"),
                resultSet.getString("display_name"),
                resultSet.getString("business_type"),
                resultSet.getObject("logo_asset_id", java.util.UUID.class),
                resultSet.getObject("favicon_asset_id", java.util.UUID.class),
                resultSet.getString("design_tokens"),
                resultSet.getString("localized_texts"),
                resultSet.getString("hero"),
                resultSet.getString("seo_metadata"),
                resultSet.getString("public_phone"),
                resultSet.getString("public_phone_label"),
                resultSet.getString("public_email"),
                resultSet.getString("address_text"),
                resultSet.getString("working_hours"),
                resultSet.getString("working_hours_text"),
                resultSet.getBigDecimal("latitude"),
                resultSet.getBigDecimal("longitude"),
                resultSet.getString("social_links"));
        String featureKey = resultSet.getString("feature_key");
        StoredFeature feature = featureKey == null
                ? null
                : new StoredFeature(
                        featureKey,
                        resultSet.getBoolean("feature_enabled"),
                        resultSet.getString("feature_config"));
        return new StoredRow(configuration, feature);
    }

    private record StoredRow(StoredSiteConfiguration configuration, StoredFeature feature) {
    }
}
