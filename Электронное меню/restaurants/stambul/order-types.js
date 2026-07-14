"use strict";

/**
 * Stambul restaurant order type configuration.
 *
 * Defines which order types are available and how they map to
 * feature flags and API codes.
 *
 * API code is always lowercase English ("preorder", "delivery", "pickup")
 * regardless of display label language.
 */

window.RESTAURANT_STAMBUL_ORDER_TYPES = Object.freeze([
  Object.freeze({ code: "preorder", label: "Предзаказ", legacyValue: "Предзаказ", feature: "preorder", default: true }),
  Object.freeze({ code: "delivery", label: "Доставка", legacyValue: "Доставка", feature: "delivery" }),
  Object.freeze({ code: "pickup", label: "Самовывоз", legacyValue: "Самовывоз", feature: "pickup" })
]);
