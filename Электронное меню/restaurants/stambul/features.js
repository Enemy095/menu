"use strict";

/**
 * Stambul restaurant feature flags.
 *
 * Mirrors the business_features PostgreSQL table exactly.
 * Future API response shape:
 *   GET /api/v1/public/stambul/restaurant → { ..., features: { ... } }
 *
 * Adding a feature:
 *   1. Add the boolean here
 *   2. Register a UI module in features/feature-registry.js
 *   3. The engine mounts/unmounts automatically
 */

window.RESTAURANT_STAMBUL_FEATURES = Object.freeze({
  orders: true,
  contacts: true,
  geoLocation: true,
  socialLinks: false,
  delivery: true,
  pickup: true,
  preorder: true,
  customTheme: true,
  whatsappFallback: true,
  promotions: false,
  loyalty: false,
  reservations: false
});
