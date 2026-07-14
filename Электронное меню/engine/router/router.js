"use strict";

/**
 * Router abstraction — decouples slug resolution from routing implementation.
 *
 * The router exposes a uniform interface. Concrete strategies (hash, history, memory)
 * are swapped via setStrategy() without touching UI or business logic.
 *
 * Aligned with backend: slugs map 1:1 to business.slug in PostgreSQL.
 */

const Router = (() => {
  /** @type {{ getSlug: () => string, onChange: (cb: (slug: string) => void) => () => void, start: () => void, destroy: () => void } | null} */
  let strategy = null;
  /** @type {Set<(slug: string) => void>} */
  const listeners = new Set();
  /** @type {string} */
  let defaultSlug = "";
  /** @type {string[]} */
  let availableSlugs = [];
  /** @type {(() => void) | null} Unsubscribe function from current strategy listener */
  let strategyUnsubscribe = null;
  /** @type {boolean} Whether start() has been called */
  let started = false;

  /**
   * Register a routing strategy.
   * @param {{ getSlug: () => string, onChange: (cb: (slug: string) => void) => () => void, start: () => void, destroy: () => void }} s
   */
  function setStrategy(s) {
    if (strategyUnsubscribe) {
      strategyUnsubscribe();
      strategyUnsubscribe = null;
    }
    if (strategy) strategy.destroy();
    strategy = s;
    started = false;
  }

  /**
   * Set the default slug used when URL contains no recognized slug.
   * @param {string} slug
   */
  function setDefault(slug) {
    defaultSlug = slug;
  }

  /**
   * Set the list of recognized slugs.
   * @param {string[]} slugs
   */
  function setAvailable(slugs) {
    availableSlugs = slugs;
  }

  /** @returns {string} Current restaurant slug, falling back to default. */
  function getSlug() {
    if (!strategy) return defaultSlug;
    const raw = strategy.getSlug();
    return availableSlugs.includes(raw) ? raw : defaultSlug;
  }

  /**
   * Subscribe to slug changes.
   * @param {(slug: string) => void} callback
   * @returns {() => void} Unsubscribe function.
   */
  function onChange(callback) {
    listeners.add(callback);
    return () => listeners.delete(callback);
  }

  /** Start listening. Idempotent — safe to call multiple times. */
  function start() {
    if (!strategy) return;

    // Unsubscribe previous strategy listener (idempotent)
    if (strategyUnsubscribe) {
      strategyUnsubscribe();
      strategyUnsubscribe = null;
    }

    // Register new strategy listener
    strategyUnsubscribe = strategy.onChange(slug => {
      const resolved = availableSlugs.includes(slug) ? slug : (defaultSlug || slug);
      listeners.forEach(cb => cb(resolved));
    });

    started = true;
    strategy.start();
  }

  /** Destroy current strategy and clear all state. */
  function destroy() {
    if (strategyUnsubscribe) {
      strategyUnsubscribe();
      strategyUnsubscribe = null;
    }
    if (strategy) strategy.destroy();
    strategy = null;
    started = false;
    listeners.clear();
  }

  return Object.freeze({ setStrategy, setDefault, setAvailable, getSlug, onChange, start, destroy });
})();

if (typeof window !== "undefined") window.Router = Router;
