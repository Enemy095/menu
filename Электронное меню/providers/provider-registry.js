"use strict";

/**
 * Provider registry — selects the appropriate data provider.
 *
 * Provider selection is configurable, not hardcoded to globals:
 *   - Explicit mode setting via configure()
 *   - URL param ?provider=json|api (testing override)
 *   - Default: "json" (safe default, no backend dependency)
 *
 * The engine calls createProvider(slug) and works through the
 * uniform RestaurantProvider interface regardless of source.
 */

const ProviderRegistry = (() => {

  /** @type {"json" | "api" | null} Explicit override (null = auto-detect) */
  let explicitMode = null;
  /** @type {string} Backend base URL for API provider */
  let apiBaseUrl = "";

  /**
   * Configure the provider registry.
   * Call this instead of reading from APP_CONFIG.
   *
   * @param {{ mode?: "json" | "api", apiBaseUrl?: string }} options
   */
  function configure(options = {}) {
    if (options.mode) explicitMode = options.mode;
    if (options.apiBaseUrl !== undefined) apiBaseUrl = String(options.apiBaseUrl || "").replace(/\/+$/, "");
  }

  /**
   * Determine which provider mode to use.
   * @returns {"json" | "api"}
   */
  function resolveMode() {
    // 1. Explicit override (from configure())
    if (explicitMode) return explicitMode;

    // 2. URL param override for testing
    const params = new URLSearchParams(window.location.search);
    const override = params.get("provider");
    if (override === "api" || override === "json") return override;

    // 3. Auto-detect: use API if backend URL is configured, otherwise JSON
    return apiBaseUrl ? "api" : "json";
  }

  /**
   * Create a provider instance for the given slug.
   * @param {string} slug
   * @returns {{ getConfig: Function, getMenu: Function, isAvailable: Function }}
   */
  function createProvider(slug) {
    const mode = resolveMode();

    if (mode === "api") {
      window.ApiRestaurantProvider.configure(apiBaseUrl);
      return {
        getConfig: () => window.ApiRestaurantProvider.getConfig(slug),
        getMenu: () => window.ApiRestaurantProvider.getMenu(slug),
        isAvailable: () => window.ApiRestaurantProvider.isAvailable(slug)
      };
    }

    return {
      getConfig: () => window.JsonRestaurantProvider.getConfig(slug),
      getMenu: () => window.JsonRestaurantProvider.getMenu(slug),
      isAvailable: () => window.JsonRestaurantProvider.isAvailable(slug)
    };
  }

  /**
   * Get current provider mode (for diagnostics).
   * @returns {string}
   */
  function getMode() {
    return resolveMode();
  }

  return Object.freeze({ configure, createProvider, getMode });
})();

if (typeof window !== "undefined") window.ProviderRegistry = ProviderRegistry;
