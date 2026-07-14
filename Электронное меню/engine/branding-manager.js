"use strict";

/**
 * Branding module — resolves and applies restaurant branding assets.
 *
 * Branding ≠ Theme. Branding is the restaurant's identity assets:
 *   - logo
 *   - favicon
 *   - hero image
 *   - social preview image
 *
 * Theme is the visual design system (colors, radii, shadows).
 * A theme can be shared by multiple restaurants.
 * Branding is always per-restaurant.
 *
 * Asset resolution:
 *   1. Check restaurants/{slug}/branding/ for local assets
 *   2. Fall back to brand.logoUrl from restaurant config
 *   3. Fall back to engine defaults
 */

const Branding = (() => {

  /** @type {Record<string, string>} Asset type → resolved path */
  const resolved = {};

  /**
   * Resolve branding assets for a restaurant.
   * @param {string} slug
   * @param {object} brandConfig - The brand section of restaurant config
   */
  function resolve(slug, brandConfig) {
    resolved.logo = brandConfig?.logoUrl || `./restaurants/${slug}/branding/logo.png`;
    resolved.favicon = `./restaurants/${slug}/branding/favicon.ico`;
    resolved.hero = `./restaurants/${slug}/branding/hero.jpg`;
    resolved.alt = brandConfig?.logoAlt || `Логотип ${brandConfig?.displayName || ""}`;
  }

  /**
   * Get a resolved branding asset path.
   * @param {"logo" | "favicon" | "hero"} type
   * @returns {string}
   */
  function getAsset(type) {
    return resolved[type] || "";
  }

  /**
   * Apply branding to the document (meta tags, favicon).
   * Does NOT touch CSS — that's the theme's job.
   *
   * Theme-color meta tag is read from the computed CSS variable,
   * not from an inline config object. This ensures it works whether
   * the theme is applied via CSS file or runtime applyTheme() override.
   *
   * @param {object} brandConfig
   * @param {object} profileConfig
   */
  function applyToDocument(brandConfig, profileConfig) {
    if (!brandConfig) return;

    // Favicon (if file exists)
    const faviconLink = document.querySelector("link[rel='icon']");
    if (faviconLink && resolved.favicon) {
      // Only set if the branding file exists — avoid 404s
      // In practice, this would need a filesystem check or try/catch on load
    }

    // Meta tags
    const metaDesc = document.querySelector('meta[name="description"]');
    if (metaDesc) {
      metaDesc.content = brandConfig.metaDescription || `Электронное меню ${brandConfig.displayName}`;
    }

    // Theme-color: read from the applied CSS custom property, not from config.
    // The theme CSS file sets --color-primary; runtime applyTheme() may override it.
    const themeColor = document.querySelector('meta[name="theme-color"]');
    if (themeColor) {
      const computed = getComputedStyle(document.documentElement).getPropertyValue("--color-primary").trim();
      if (computed) {
        themeColor.content = computed;
      }
    }

    // Title
    if (brandConfig.pageTitle) {
      document.title = brandConfig.pageTitle;
    }
  }

  /**
   * Clear resolved assets (for slug change).
   */
  function clear() {
    Object.keys(resolved).forEach(key => delete resolved[key]);
  }

  return Object.freeze({ resolve, getAsset, applyToDocument, clear });
})();

if (typeof window !== "undefined") window.Branding = Branding;
