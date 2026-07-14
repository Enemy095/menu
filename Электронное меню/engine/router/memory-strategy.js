"use strict";

/**
 * In-memory routing strategy.
 *
 * For testing and programmatic control — no URL interaction.
 * Useful for self-tests, SSR, and headless environments.
 */

const MemoryStrategy = (() => {
  let currentSlug = "";
  /** @type {((slug: string) => void) | null} */
  let listener = null;

  function getSlug() {
    return currentSlug;
  }

  function setSlug(slug) {
    currentSlug = slug;
    if (listener) listener(slug);
  }

  function onChange(callback) {
    listener = callback;
    return () => { if (listener === callback) listener = null; };
  }

  function start() {
    if (listener) listener(currentSlug);
  }

  function destroy() {
    listener = null;
    currentSlug = "";
  }

  return Object.freeze({ getSlug, setSlug, onChange, start, destroy });
})();

if (typeof window !== "undefined") window.MemoryStrategy = MemoryStrategy;
