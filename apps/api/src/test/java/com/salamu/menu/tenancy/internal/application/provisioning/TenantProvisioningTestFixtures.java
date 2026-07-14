package com.salamu.menu.tenancy.internal.application.provisioning;

import java.util.ArrayList;
import java.util.List;

import com.salamu.menu.tenancy.api.SuspendedTenantSpec;
import com.salamu.menu.tenancy.api.TenantAssetId;
import com.salamu.menu.tenancy.api.TenantConfigurationSnapshot;
import com.salamu.menu.tenancy.api.TenantFeatureKey;
import com.salamu.menu.tenancy.api.TenantId;

final class TenantProvisioningTestFixtures {

    private TenantProvisioningTestFixtures() {
    }

    static TenantConfigurationSnapshot configuration(
            TenantId tenantId,
            SuspendedTenantSpec identity,
            TenantAssetId logoAssetId) {
        return configuration(tenantId, identity, logoAssetId, "Restaurant " + identity.slug());
    }

    static TenantConfigurationSnapshot configuration(
            TenantId tenantId,
            SuspendedTenantSpec identity,
            TenantAssetId logoAssetId,
            String displayName) {
        return new TenantConfigurationSnapshot(
                tenantId,
                identity,
                new TenantConfigurationSnapshot.Branding(
                        displayName,
                        "restaurant",
                        logoAssetId,
                        null,
                        new TenantConfigurationSnapshot.DesignTokens(
                                "#112233",
                                "#223344",
                                "#334455",
                                "#445566",
                                "#556677",
                                "#667788",
                                "#778899",
                                "12px",
                                "sans-serif"),
                        List.of(new TenantConfigurationSnapshot.LocalizedText(
                                identity.locale(),
                                displayName,
                                displayName,
                                "Restaurant",
                                "Cuisine",
                                "Slogan",
                                "Introduction",
                                "Menu",
                                "Cart",
                                "Order type")),
                        new TenantConfigurationSnapshot.Hero(
                                null,
                                null,
                                "Menu",
                                "Welcome",
                                "Fresh food",
                                "View menu",
                                List.of()),
                        new TenantConfigurationSnapshot.SeoMetadata(
                                displayName,
                                "Restaurant menu",
                                "https://example.test/" + identity.slug(),
                                null)),
                new TenantConfigurationSnapshot.Profile(
                        "+79990000000",
                        "Call restaurant",
                        null,
                        null,
                        List.of(),
                        "Daily 10:00-23:00",
                        null,
                        null,
                        List.of()),
                features());
    }

    private static List<TenantConfigurationSnapshot.Feature> features() {
        var values = new ArrayList<TenantConfigurationSnapshot.Feature>();
        for (var key : TenantFeatureKey.values()) {
            if (key == TenantFeatureKey.ORDERS) {
                values.add(new TenantConfigurationSnapshot.Feature(
                        key,
                        true,
                        null,
                        false,
                        new TenantConfigurationSnapshot.OrderLimits(
                                99, 200, 80, 32, 240, 500, null, null, 15)));
            } else if (key == TenantFeatureKey.PREORDER) {
                values.add(new TenantConfigurationSnapshot.Feature(key, true, "Preorder", true, null));
            } else if (key == TenantFeatureKey.DELIVERY) {
                values.add(new TenantConfigurationSnapshot.Feature(key, true, "Delivery", false, null));
            } else if (key == TenantFeatureKey.PICKUP) {
                values.add(new TenantConfigurationSnapshot.Feature(key, true, "Pickup", false, null));
            } else {
                values.add(new TenantConfigurationSnapshot.Feature(key, false, null, false, null));
            }
        }
        return List.copyOf(values);
    }
}
