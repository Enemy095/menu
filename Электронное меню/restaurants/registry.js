"use strict";

/**
 * Restaurant registry — maps slugs to their data factories.
 *
 * This is the single source of truth for which restaurants exist.
 * Adding a restaurant = adding one entry here + creating the config files.
 *
 * Each entry provides factory functions (not pre-evaluated data) so
 * configs are loaded lazily and can be hot-swapped.
 */

const RestaurantRegistry = (() => {
  /** @type {Record<string, { config: () => object, menu: () => object, features: () => object }>} */
  const registry = {
    stambul: {
      config: () => window.RESTAURANT_STAMBUL,
      menu: () => window.RESTAURANT_STAMBUL_MENU,
      features: () => window.RESTAURANT_STAMBUL_FEATURES
    },
    adamas: {
      config: () => window.RESTAURANT_ADAMAS,
      menu: () => window.RESTAURANT_ADAMAS_MENU,
      features: () => window.RESTAURANT_ADAMAS_FEATURES
    }
  };

  const defaultSlug = "adamas";

  /**
   * Get the factory functions for a slug.
   * @param {string} slug
   * @returns {{ config: () => object, menu: () => object } | null}
   */
  function get(slug) {
    return registry[slug] || null;
  }

  /**
   * Check if a slug is registered.
   * @param {string} slug
   * @returns {boolean}
   */
  function has(slug) {
    return slug in registry;
  }

  /**
   * Get all registered slugs.
   * @returns {string[]}
   */
  function slugs() {
    return Object.keys(registry);
  }

  /**
   * Get the default restaurant slug.
   * @returns {string}
   */
  function getDefault() {
    return defaultSlug;
  }

  /**
   * Register a new restaurant (for dynamic registration).
   * @param {string} slug
   * @param {{ config: () => object, menu: () => object }} entry
   */
  function register(slug, entry) {
    registry[slug] = entry;
  }

  return Object.freeze({ get, has, slugs, getDefault, register });
})();

if (typeof window !== "undefined") window.RestaurantRegistry = RestaurantRegistry;
