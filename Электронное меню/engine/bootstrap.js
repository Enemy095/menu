"use strict";

/**
 * Engine bootstrap — entry point for the SaaS architecture.
 *
 * This module wires together: Router → Provider → State → Branding → Theme → Features.
 *
 * IMPORTANT: Phase 0 bootstrap does NOT replace the existing app.js init().
 * It runs IN PARALLEL, setting up the new infrastructure while the old code
 * continues to use the original globals. This ensures zero regressions.
 *
 * Phase 3 will replace app.js init() with a full engine bootstrap.
 */

const EngineBootstrap = (() => {

  /**
   * Initialize the engine infrastructure.
   * Call this AFTER existing globals are loaded (config.js, menu.js).
   * Does NOT modify any existing globals or DOM.
   */
  async function init() {
    // 1. Register the HashStrategy (default for static hosting)
    window.Router.setStrategy(window.HashStrategy);

    // 2. Configure available slugs from registry
    const slugs = window.RestaurantRegistry.slugs();
    window.Router.setAvailable(slugs);
    window.Router.setDefault(window.RestaurantRegistry.getDefault());

    // 3. Wire up JsonRestaurantProvider with the registry
    const restaurantFactories = {};
    for (const slug of slugs) {
      restaurantFactories[slug] = window.RestaurantRegistry.get(slug);
    }
    window.JsonRestaurantProvider.registerRestaurants(restaurantFactories);
    console.log("Registered restaurants:", restaurantFactories);
    
    // 4. Configure provider registry (no APP_CONFIG dependency)
    //    Reads from legacy globals for backward compat during Phase 0,
    //    but through an explicit configure() call, not direct global access.
    const apiBaseUrl = window.APP_CONFIG?.apiBaseUrl || "";
    window.ProviderRegistry.configure({ apiBaseUrl });

    // 5. Register feature modules
    window.FeatureRegistry.register("contacts", window.FeatureContacts);
    window.FeatureRegistry.register("promotions", window.FeaturePromotions);
    window.FeatureRegistry.register("delivery", window.FeatureDelivery);
    window.FeatureRegistry.register("loyalty", window.FeatureLoyalty);
    window.FeatureRegistry.register("reservations", window.FeatureReservations);
    window.FeatureRegistry.register("deliveryBlocks", window.FeatureDeliveryBlocks);

    // 6. Resolve current slug (but don't apply — old code still runs)
    const slug = window.Router.getSlug();
    window.EngineState.setSlug(slug);

    // 7. Load provider data (but don't render — old code still renders)
    try {
      const provider = window.ProviderRegistry.createProvider(slug);
      const config = await provider.getConfig();
      const menu = await provider.getMenu();

      // Merge features from single source of truth (features.js)
      // into the config object. Config no longer embeds features inline.
      const registryEntry = window.RestaurantRegistry.get(slug);
      if (registryEntry?.features) {
        const features = registryEntry.features();
        // Create a new config-like object with features merged in
        const configWithFeatures = { ...config, features };
        window.EngineState.setConfig(configWithFeatures);
      } else {
        window.EngineState.setConfig(config);
      }

      window.EngineState.setMenu(menu);

      // 8. Apply theme (non-destructive — adds <link>, doesn't remove existing styles)
      const resolvedConfig = window.EngineState.getConfig();
      if (resolvedConfig.theme) {
        window.ThemeRegistry.apply(resolvedConfig.theme);
      }

      // 9. Resolve branding assets
      window.Branding.resolve(slug, resolvedConfig.brand);

      // 10. Sync feature modules (mounts deliveryBlocks for Adamas, etc.)
      if (resolvedConfig.features) {
        const resolvedMenu = window.EngineState.getMenu();
        window.FeatureRegistry.sync(resolvedConfig.features, {
          menuItems: resolvedMenu?.items || [],
          categories: resolvedMenu?.categories || [],
          config: resolvedConfig
        });
      }

    } catch (error) {
      console.warn(`Engine bootstrap: failed to load restaurant "${slug}":`, error.message);
    }

    // 10. Start router listening.
    // NOTE: Do NOT register an onChange listener here — app.js registers
    // loadRestaurant() which handles the full switch (provider, theme,
    // branding, features, re-render). A listener registered before
    // loadRestaurant would set the slug first, causing loadRestaurant's
    // old-slug guard to bail out.
    window.Router.start();
  }

  return Object.freeze({ init });
})();

if (typeof window !== "undefined") window.EngineBootstrap = EngineBootstrap;
