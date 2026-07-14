"use strict";

/**
 * Centralized application state.
 *
 * This module owns ALL mutable application state.
 * Components read from and write to this state — never to globals.
 *
 * State is scoped per restaurant via slug-prefixed localStorage keys.
 * This prevents cart data collision when users visit multiple restaurants.
 */

const EngineState = (() => {
  /** @type {string} Current restaurant slug (set by bootstrap, not hardcoded) */
  let currentSlug = "";

  /** @type {object|null} Current restaurant config (loaded from provider) */
  let currentConfig = null;

  /** @type {object|null} Current menu version (loaded from provider) */
  let currentMenu = null;

  /** @type {string} localStorage key prefix (per-restaurant) */
  const STORAGE_PREFIX = "menu-cart";

  // ── Cart state ──

  /** @type {Map<string, number>} itemId → quantity */
  const cart = new Map();

  /** @type {string} Current category filter */
  let category = "Все";

  /** @type {string} Selected order type code */
  let selectedOrderType = "preorder";

  /** @type {object|null} Pending order draft for idempotency */
  let pendingOrder = null;

  /** @type {boolean} Whether an order submission is in progress */
  let isSubmitting = false;

  // ── Getters ──

  function getSlug() { return currentSlug; }
  function getConfig() { return currentConfig; }
  function getMenu() { return currentMenu; }
  function getMenuItems() { return currentMenu?.items || []; }
  function getMenuCategories() { return currentMenu?.categories || []; }
  function getMenuVersion() { return currentMenu?.version || 0; }
  function getCategory() { return category; }
  function getSelectedOrderType() { return selectedOrderType; }
  function getPendingOrder() { return pendingOrder; }
  function getIsSubmitting() { return isSubmitting; }
  function getCart() { return cart; }

  // ── Setters ──

  function setSlug(slug) { currentSlug = slug; }
  function setConfig(config) { currentConfig = config; }
  function setMenu(menu) { currentMenu = menu; }
  function setCategory(c) { category = c; }
  function setSelectedOrderType(type) { selectedOrderType = type; }
  function setPendingOrder(order) { pendingOrder = order; }
  function setIsSubmitting(v) { isSubmitting = v; }

  // ── Storage (per-restaurant) ──

  function getStorageKey() {
    return `${currentSlug}-${STORAGE_PREFIX}-v1`;
  }

  function saveCart() {
    const data = {
      cart: Object.fromEntries(cart),
      selectedOrderType
    };
    try {
      localStorage.setItem(getStorageKey(), JSON.stringify(data));
    } catch (error) {
      console.warn("Не удалось сохранить корзину:", error);
    }
  }

  function loadCart() {
    let raw;
    try {
      raw = localStorage.getItem(getStorageKey());
      if (!raw) return;
    } catch (error) {
      console.warn("Хранилище недоступно:", error);
      return;
    }
    try {
      const saved = JSON.parse(raw);
      if (saved && typeof saved === "object" && !Array.isArray(saved)) {
        if (saved.cart && typeof saved.cart === "object") {
          const validTypes = ["preorder", "delivery", "pickup", "Предзаказ", "Доставка", "Самовывоз"];
          if (validTypes.includes(saved.selectedOrderType)) {
            selectedOrderType = saved.selectedOrderType;
          }
          const items = getMenuItems();
          Object.entries(saved.cart).forEach(([id, quantity]) => {
            if (items.some(item => item.id === id) && Number.isInteger(quantity) && quantity > 0) {
              cart.set(id, quantity);
            }
          });
        }
      }
    } catch {
      try { localStorage.removeItem(getStorageKey()); } catch { /* ignore */ }
      console.warn("Повреждённое сохранение корзины очищено");
    }
  }

  function clearCartStorage() {
    try { localStorage.removeItem(getStorageKey()); } catch { /* ignore */ }
  }

  // ── Cart operations ──

  function getCartCount() {
    return [...cart.values()].reduce((sum, q) => sum + q, 0);
  }

  function getCartTotal() {
    const items = getMenuItems();
    return [...cart].reduce((sum, [id, quantity]) => {
      const item = items.find(i => i.id === id);
      return sum + (item ? item.price * quantity : 0);
    }, 0);
  }

  function getCartLines() {
    const items = getMenuItems();
    return [...cart].map(([id, quantity]) => {
      const item = items.find(i => i.id === id);
      return item ? { ...item, quantity, total: item.price * quantity } : null;
    }).filter(Boolean);
  }

  function changeQuantity(id, delta) {
    const limits = currentConfig?.limits || {};
    const maxItem = limits.maxItemQuantity || 99;
    const maxTotal = limits.maxTotalItems || 200;

    const items = getMenuItems();
    if (!items.some(item => item.id === id)) return;

    const current = cart.get(id) || 0;
    const currentTotal = getCartCount();
    const allowedDelta = delta > 0 ? Math.min(delta, maxTotal - currentTotal) : delta;
    const next = Math.min(maxItem, Math.max(0, current + allowedDelta));

    if (next === 0) cart.delete(id);
    else cart.set(id, next);
  }

  function clearAll() {
    cart.clear();
    pendingOrder = null;
    isSubmitting = false;
  }

  return Object.freeze({
    getSlug, getConfig, getMenu, getMenuItems, getMenuCategories, getMenuVersion,
    getCategory, getSelectedOrderType, getPendingOrder, getIsSubmitting, getCart,
    setSlug, setConfig, setMenu, setCategory, setSelectedOrderType, setPendingOrder, setIsSubmitting,
    saveCart, loadCart, clearCartStorage,
    getCartCount, getCartTotal, getCartLines, changeQuantity, clearAll
  });
})();

if (typeof window !== "undefined") window.EngineState = EngineState;
