"use strict";

/**
 * Hash-based routing strategy.
 *
 * Uses location.hash (#/slug) for static hosting compatibility.
 * Can be replaced by HistoryStrategy for clean URLs when server config is available.
 *
 * Interface contract (same for all strategies):
 *   getSlug()                    → string
 *   onChange(cb)                  → unsubscribe fn
 *   start()                      → void
 *   destroy()                    → void
 */

const HashStrategy = (() => {
  /** @type {((slug: string) => void) | null} */
  let listener = null;

  function getSlug() {
    const hash = window.location.hash.replace(/^#\/?/, "").split("/")[0].split("?")[0];
    return hash || "";
  }

  function onChange(callback) {
    listener = callback;
    const handler = () => {
      if (listener) listener(getSlug());
    };
    window.addEventListener("hashchange", handler);
    return () => {
      window.removeEventListener("hashchange", handler);
      if (listener === callback) listener = null;
    };
  }

  function start() {
    if (listener) listener(getSlug());
  }

  function destroy() {
    listener = null;
  }

  return Object.freeze({ getSlug, onChange, start, destroy });
})();

if (typeof window !== "undefined") window.HashStrategy = HashStrategy;
