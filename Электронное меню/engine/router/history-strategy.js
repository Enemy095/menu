"use strict";

/**
 * History-based routing strategy.
 *
 * Uses pushState/popState for clean URLs (/stambul, /rizvan).
 * Requires server-side fallback (all routes serve index.html).
 *
 * Drop-in replacement for HashStrategy — same interface contract.
 */

const HistoryStrategy = (() => {
  /** @type {((slug: string) => void) | null} */
  let listener = null;
  /** @type {string} */
  let basePath = "/";

  /**
   * @param {{ basePath?: string }} [options]
   */
  function configure(options = {}) {
    if (options.basePath) basePath = options.basePath;
  }

  function getSlug() {
    const path = window.location.pathname;
    const relative = path.startsWith(basePath) ? path.slice(basePath.length) : path;
    const slug = relative.split("/")[0].split("?")[0];
    return slug || "";
  }

  function navigate(slug) {
    window.history.pushState({ slug }, "", `${basePath}${slug}`);
    // Fire popstate so the Router's onChange wrapper handles slug validation.
    // Do NOT call listener directly — that bypasses Router's slug resolution.
    window.dispatchEvent(new PopStateEvent("popstate", { state: { slug } }));
  }

  function onChange(callback) {
    listener = callback;
    const handler = () => {
      if (listener) listener(getSlug());
    };
    window.addEventListener("popstate", handler);
    return () => {
      window.removeEventListener("popstate", handler);
      if (listener === callback) listener = null;
    };
  }

  function start() {
    if (listener) listener(getSlug());
  }

  function destroy() {
    listener = null;
  }

  return Object.freeze({ configure, getSlug, navigate, onChange, start, destroy });
})();

if (typeof window !== "undefined") window.HistoryStrategy = HistoryStrategy;
