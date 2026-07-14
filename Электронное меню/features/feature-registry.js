"use strict";

/**
 * Feature registry — maps feature flags to optional UI modules.
 *
 * Feature flags mirror the business_features PostgreSQL table exactly.
 * The engine reads a restaurant's features and mounts only enabled modules.
 *
 * Adding a new feature:
 *   1. Add boolean flag to restaurant's features.js
 *   2. Create module in features/ that implements { mount, unmount }
 *   3. Register it here: FeatureRegistry.register("featureName", moduleFactory)
 *   4. Engine auto-mounts when feature is enabled
 */

const FeatureRegistry = (() => {
  /** @type {Record<string, { mount: (ctx: object) => void, unmount: () => void }>} */
  const modules = {};
  /** @type {Set<string>} Currently mounted feature names */
  const mounted = new Set();

  /**
   * Register a feature module.
   * @param {string} name - Must match a key in FeatureFlags
   * @param {{ mount: (ctx: object) => void, unmount: () => void }} mod
   */
  function register(name, mod) {
    modules[name] = mod;
  }

  /**
   * Mount all features enabled in the given flags.
   * Unmounts previously mounted features that are now disabled.
   *
   * @param {import("../providers/restaurant-provider.js").FeatureFlags} features
   * @param {object} context - Passed to each module's mount()
   */
  function sync(features, context = {}) {
    // Unmount features that are now disabled
    for (const name of mounted) {
      if (!features[name] && modules[name]) {
        modules[name].unmount();
        mounted.delete(name);
      }
    }
    // Mount features that are now enabled
    for (const [name, mod] of Object.entries(modules)) {
      if (features[name] && !mounted.has(name)) {
        mod.mount(context);
        mounted.add(name);
      }
    }
  }

  /**
   * Unmount all currently mounted features.
   */
  function unmountAll() {
    for (const name of mounted) {
      if (modules[name]) modules[name].unmount();
    }
    mounted.clear();
  }

  /**
   * Get list of registered feature names.
   * @returns {string[]}
   */
  function list() {
    return Object.keys(modules);
  }

  return Object.freeze({ register, sync, unmountAll, list });
})();

if (typeof window !== "undefined") window.FeatureRegistry = FeatureRegistry;
