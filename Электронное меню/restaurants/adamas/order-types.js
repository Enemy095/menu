"use strict";

/**
 * Adamas restaurant order type configuration.
 */

window.RESTAURANT_ADAMAS_ORDER_TYPES = Object.freeze([
  Object.freeze({ code: "preorder", label: "Предзаказ", legacyValue: "Предзаказ", feature: "preorder", default: true }),
  Object.freeze({ code: "delivery", label: "Доставка", legacyValue: "Доставка", feature: "delivery" }),
  Object.freeze({ code: "pickup", label: "Самовывоз", legacyValue: "Самовывоз", feature: "pickup" })
]);
