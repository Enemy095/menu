"use strict";

/**
 * Contacts feature module.
 *
 * Renders the restaurant contact section (phone, address, hours, map, social links).
 * Reads config from EngineState and delegates rendering to app.js renderContacts().
 */

window.FeatureContacts = (() => {
  /** @type {HTMLElement|null} */
  let section = null;

  function mount(context) {
    section = context.contactSection || document.querySelector("#contactSection");
    const c = window.EngineState.getConfig() || {};
    if (typeof window.renderContacts === "function") {
      window.renderContacts(c.features || {}, c.profile || {});
    }
  }

  function unmount() {
    if (section) {
      section.replaceChildren();
      section.hidden = true;
    }
  }

  return Object.freeze({ mount, unmount });
})();
