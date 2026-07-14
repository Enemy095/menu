"use strict";

/**
 * JSON-based restaurant provider.
 *
 * Reads restaurant data from local JavaScript config files.
 * Used for static deployment without a backend.
 *
 * Data sources:
 *   restaurants/{slug}/config.js   → RestaurantConfig
 *   restaurants/{slug}/features.js → FeatureFlags
 *   restaurants/{slug}/order-types.js → OrderTypeConfig[]
 *   restaurants/{slug}/menu.js     → MenuVersion { version, publishedAt, categories, items }
 *   restaurants/{slug}/branding/   → Static assets (logo, favicon, hero)
 */

const JsonRestaurantProvider = (() => {

  /** @type {Record<string, object>} Cache: slug → loaded config */
  const configCache = {};
  /** @type {Record<string, object>} Cache: slug → loaded menu */
  const menuCache = {};

  /**
   * Load a restaurant's configuration from its config file.
   * Results are cached after first load.
   *
   * @param {string} slug
   * @returns {Promise<import("./restaurant-provider.js").RestaurantConfig>}
   */
  async function getConfig(slug) {
    if (configCache[slug]) return configCache[slug];

    const configModule = restaurants[slug]?.config;
    if (!configModule) throw new Error(`Restaurant not found: ${slug}`);

    const config = configModule();
    configCache[slug] = config;
    return config;
  }

  /**
   * Load a restaurant's menu with version metadata.
   * Returns MenuVersion aligned with the menu_versions backend table.
   *
   * @param {string} slug
   * @returns {Promise<import("./restaurant-provider.js").MenuVersion>}
   */
  async function getMenu(slug) {
    if (menuCache[slug]) return menuCache[slug];

    const menuFactory = restaurants[slug]?.menu;
    if (!menuFactory) throw new Error(`Menu not found for: ${slug}`);

    const raw = menuFactory();

    // Support both MenuVersion shape { version, publishedAt, categories, items }
    // and legacy flat array shape [item, item, ...]
    let items;
    let categories;
    let version;
    let publishedAt;

    if (raw && typeof raw === "object" && Array.isArray(raw.items)) {
      // MenuVersion shape (new)
      items = raw.items;
      categories = raw.categories || [...new Set(items.map(item => item.category))];
      version = raw.version || 1;
      publishedAt = raw.publishedAt || null;
    } else if (Array.isArray(raw)) {
      // Legacy flat array shape
      items = raw;
      categories = [...new Set(items.map(item => item.category))];
      version = 1;
      publishedAt = null;
    } else {
      throw new Error(`Invalid menu format for: ${slug}`);
    }

    /** @type {import("./restaurant-provider.js").MenuVersion} */
    const menuVersion = {
      version,
      publishedAt,
      categories: Object.freeze(categories),
      items: Object.freeze(items.map(item => Object.freeze({ ...item })))
    };

    menuCache[slug] = menuVersion;
    return menuVersion;
  }

  /**
   * Check if a restaurant is available (has config and menu).
   * @param {string} slug
   * @returns {Promise<boolean>}
   */
  async function isAvailable(slug) {
    try {
      await getConfig(slug);
      await getMenu(slug);
      return true;
    } catch {
      return false;
    }
  }

  /**
   * Clear cached data for a slug (useful for hot-reload).
   * @param {string} [slug] - If omitted, clears all caches.
   */
  function clearCache(slug) {
    if (slug) {
      delete configCache[slug];
      delete menuCache[slug];
    } else {
      Object.keys(configCache).forEach(key => delete configCache[key]);
      Object.keys(menuCache).forEach(key => delete menuCache[key]);
    }
  }

  // Will be populated by restaurant-registry.js after it loads restaurant modules.
  // This is intentionally a mutable reference so the registry can inject loaders.
  /** @type {Record<string, { config: () => object, menu: () => object[] }>} */
  let restaurants = {};

  /**
   * Register restaurant data factories (called by restaurant-registry).
   * @param {Record<string, { config: () => object, menu: () => object[] }>} registry
   */
  function registerRestaurants(registry) {
    restaurants = registry;
  }

  return Object.freeze({ getConfig, getMenu, isAvailable, clearCache, registerRestaurants });
})();

if (typeof window !== "undefined") window.JsonRestaurantProvider = JsonRestaurantProvider;
