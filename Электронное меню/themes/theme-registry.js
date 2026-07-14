"use strict";

/**
 * Theme registry — maps theme names to CSS file paths.
 *
 * Themes define visual design tokens (colors, radii, shadows).
 * They do NOT contain restaurant data or branding assets.
 * A theme can be shared across multiple restaurants.
 *
 * Application flow:
 *   1. Restaurant config specifies theme: "stambul"
 *   2. ThemeRegistry resolves to CSS path
 *   3. Engine injects <link> or reads tokens
 *   4. Runtime overrides (applyTheme) handle white-label customizations
 */

const ThemeRegistry = (() => {
  /** @type {Record<string, string>} Theme name → relative CSS path */
  const themes = {
    stambul: "./themes/stambul/theme.css",
    dark: "./themes/dark/theme.css",
    delivery: "./themes/delivery/theme.css"
  };

  /** @type {string} Fallback theme when requested theme is not found */
  const fallbackTheme = "stambul";

  /**
   * Get the CSS file path for a theme.
   * @param {string} name
   * @returns {string|null} Path or null if not found
   */
  function getThemePath(name) {
    return themes[name] || null;
  }

  /**
   * Register a new theme.
   * @param {string} name
   * @param {string} cssPath
   */
  function register(name, cssPath) {
    themes[name] = cssPath;
  }

  /**
   * Get all registered theme names.
   * @returns {string[]}
   */
  function list() {
    return Object.keys(themes);
  }

  /**
   * Apply a theme by injecting its CSS file.
   * Removes any previously applied theme CSS.
   * @param {string} name
   */
  function apply(name) {
    const existing = document.querySelector("link[data-theme]");
    if (existing) existing.remove();

    const path = getThemePath(name) || getThemePath(fallbackTheme);
    if (!path) return;

    const link = document.createElement("link");
    link.rel = "stylesheet";
    link.href = path;
    link.dataset.theme = name || fallbackTheme;
    document.head.appendChild(link);
  }

  /**
   * Remove any applied theme CSS.
   */
  function clear() {
    const existing = document.querySelector("link[data-theme]");
    if (existing) existing.remove();
  }

  return Object.freeze({ getThemePath, register, list, apply, clear });
})();

if (typeof window !== "undefined") window.ThemeRegistry = ThemeRegistry;
