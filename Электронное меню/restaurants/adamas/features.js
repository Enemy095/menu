"use strict";

/**
 * Adamas restaurant feature flags.
 * Mirrors business_features PostgreSQL table.
 */

window.RESTAURANT_ADAMAS_FEATURES = Object.freeze({
  orders: true,
  contacts: true,
  geoLocation: false,
  socialLinks: false,
  delivery: true,
  pickup: true,
  preorder: true,
  customTheme: true,
  whatsappFallback: true,
  promotions: false,
  loyalty: false,
  reservations: false,
  deliveryBlocks: true
});
