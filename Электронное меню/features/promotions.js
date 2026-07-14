"use strict";

/**
 * Promotions feature module (stub).
 *
 * Future implementation: displays active promotions for a restaurant.
 * Backend endpoint: GET /api/v1/public/{slug}/promotions
 *
 * Data shape (from backend):
 *   { id, title, description, discountType, discountValue, startDate, endDate, menuItemId? }
 */

window.FeaturePromotions = (() => {
  function mount(context) {
    // Future: fetch promotions from provider, render promo banners
  }

  function unmount() {
    // Future: remove promo banners
  }

  return Object.freeze({ mount, unmount });
})();
