"use strict";

/**
 * API-based restaurant provider.
 *
 * Reads restaurant data from the Go backend.
 * Stub implementation — will be activated when backend is deployed.
 *
 * Backend endpoints (from api-contract.md):
 *   GET /api/v1/public/{slug}/restaurant  → RestaurantConfig
 *   GET /api/v1/public/{slug}/menu        → MenuVersion
 *   POST /api/v1/public/{slug}/orders     → CreateOrderResponse
 */

const ApiRestaurantProvider = (() => {

  /** @type {string} Backend base URL */
  let baseUrl = "";

  /**
   * Set the backend base URL.
   * @param {string} url
   */
  function configure(url) {
    baseUrl = String(url || "").replace(/\/+$/, "");
  }

  /**
   * Fetch restaurant config from backend.
   * @param {string} slug
   * @returns {Promise<import("./restaurant-provider.js").RestaurantConfig>}
   */
  async function getConfig(slug) {
    const response = await fetch(`${baseUrl}/api/v1/public/${slug}/restaurant`);
    if (!response.ok) {
      throw Object.assign(new Error("Restaurant not found"), { code: "NOT_FOUND", status: response.status });
    }
    return response.json();
  }

  /**
   * Fetch menu with version metadata from backend.
   * @param {string} slug
   * @returns {Promise<import("./restaurant-provider.js").MenuVersion>}
   */
  async function getMenu(slug) {
    const response = await fetch(`${baseUrl}/api/v1/public/${slug}/menu`);
    if (!response.ok) {
      throw Object.assign(new Error("Menu not found"), { code: "NOT_FOUND", status: response.status });
    }
    return response.json();
  }

  /**
   * Check if a restaurant is available via the API.
   * @param {string} slug
   * @returns {Promise<boolean>}
   */
  async function isAvailable(slug) {
    try {
      const response = await fetch(`${baseUrl}/api/v1/public/${slug}/restaurant`, { method: "HEAD" });
      return response.ok;
    } catch {
      return false;
    }
  }

  return Object.freeze({ configure, getConfig, getMenu, isAvailable });
})();

if (typeof window !== "undefined") window.ApiRestaurantProvider = ApiRestaurantProvider;
