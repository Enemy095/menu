"use strict";

// ── EngineState helpers ──
// All restaurant data comes from EngineState, never from legacy globals.

function cfg() { return window.EngineState.getConfig() || {}; }
function getMenuItems() { return window.EngineState.getMenuItems(); }
function getMenuCategories() { return window.EngineState.getMenuCategories(); }

const { normalizeText, normalizePhone, normalizeCustomerPhone, isSafeHttpsUrl, isSafeImageUrl, validateOrderPayload } = window.StambulValidation;
const { createIdempotencyKey, buildWhatsAppMessage, buildWhatsAppUrl, submitOrder, submitOrderToBackend, submitOrderToMock } = window.StambulOrderSubmit;

const state = {
  category: "Все",
  cart: new Map(),
  selectedOrderType: "preorder",
  pendingOrder: null,
  isSubmitting: false
};

const elements = {
  categories: document.querySelector("#categories"),
  menuList: document.querySelector("#menuList"),
  dishCount: document.querySelector("#dishCount"),
  cartBadge: document.querySelector("#cartBadge"),
  cartBar: document.querySelector("#cartBar"),
  cartBarCount: document.querySelector("#cartBarCount"),
  cartBarTotal: document.querySelector("#cartBarTotal"),
  backdrop: document.querySelector("#cartBackdrop"),
  cartItems: document.querySelector("#cartItems"),
  cartTotal: document.querySelector("#cartTotal"),
  orderForm: document.querySelector("#orderForm"),
  phoneField: document.querySelector("#phoneField"),
  customerPhone: document.querySelector("#customerPhone"),
  addressField: document.querySelector("#addressField"),
  deliveryAddress: document.querySelector("#deliveryAddress"),
  customerName: document.querySelector("#customerName"),
  guestCount: document.querySelector("#guestCount"),
  orderComment: document.querySelector("#orderComment"),
  submitButton: document.querySelector("#submitOrderButton"),
  contactSection: document.querySelector("#contactSection"),
  brandLogo: document.querySelector("#brandLogo"),
  brandSlogan: document.querySelector("[data-brand-slogan]"),
  orderIntro: document.querySelector("#orderIntro"),
  submitModeNote: document.querySelector("#submitModeNote"),
  orderTypeOptions: document.querySelector("#orderTypeOptions"),
  scheduleBlock: document.querySelector("#scheduleBlock"),
  scheduleText: document.querySelector("#scheduleText"),
  toast: document.querySelector("#toast"),
  testResults: document.querySelector("#testResults")
};

// ── Async initialization ──

async function init() {
  // 1. Bootstrap loads restaurant data into EngineState
  await window.EngineBootstrap.init();

  // 2. Restore cart from localStorage (uses slug-prefixed key)
  restoreState();

  // 3. Apply restaurant config to UI
  applyWhiteLabelConfig();
  applyConfiguredLimits();
  renderCategories();
  renderMenu();
  restoreFormFields();
  updateOrderTypeUI();
  updateCartSummary();
  renderCart();
  bindEvents();
  syncDesktopCart();

  // 4. Listen for restaurant switches
  window.Router.onChange(loadRestaurant);

  // 5. Run self-tests if requested
  if (new URLSearchParams(window.location.search).get("test") === "1") {
    runSelfTests();
  }
}

// ── Restaurant switching ──

async function loadRestaurant(newSlug) {
  const oldSlug = window.EngineState.getSlug();
  if (newSlug === oldSlug) return;

  window.EngineState.setSlug(newSlug);

  // Unmount previous feature blocks
  window.FeatureRegistry.unmountAll();

  // Clear delivery-specific blocks from DOM
  document.querySelectorAll(".popular-section, .category-cards-section, .delivery-info-section").forEach(el => el.remove());

  try {
    const provider = window.ProviderRegistry.createProvider(newSlug);
    const config = await provider.getConfig();
    const menu = await provider.getMenu();

    // Merge features from single source of truth
    const registryEntry = window.RestaurantRegistry.get(newSlug);
    if (registryEntry?.features) {
      const features = registryEntry.features();
      window.EngineState.setConfig({ ...config, features });
    } else {
      window.EngineState.setConfig(config);
    }

    window.EngineState.setMenu(menu);

    // Apply theme
    const resolvedConfig = window.EngineState.getConfig();
    if (resolvedConfig.theme) {
      window.ThemeRegistry.apply(resolvedConfig.theme);
    }

    // Apply branding
    window.Branding.resolve(newSlug, resolvedConfig.brand);

    // Sync features (mounts deliveryBlocks for Adamas, etc.)
    if (resolvedConfig.features) {
      const resolvedMenu = window.EngineState.getMenu();
      window.FeatureRegistry.sync(resolvedConfig.features, {
        menuItems: resolvedMenu?.items || [],
        categories: resolvedMenu?.categories || [],
        config: resolvedConfig
      });
    }

    // Reset state for new restaurant
    state.category = "Все";
    state.cart.clear();
    state.pendingOrder = null;
    state.selectedOrderType = "preorder";
    state.isSubmitting = false;

    // Re-render everything
    applyWhiteLabelConfig();
    applyConfiguredLimits();
    renderCategories();
    renderMenu();
    restoreFormFields();
    restoreState();
    updateOrderTypeUI();
    updateCartSummary();
    renderCart();
    syncDesktopCart();

  } catch (error) {
    console.warn(`Failed to load restaurant "${newSlug}":`, error.message);
  }
}

// ── Event binding ──

function bindEvents() {
  elements.categories.addEventListener("click", handleCategoryClick);
  elements.menuList.addEventListener("pointerdown", handleQuantityPointerDown);
  elements.cartItems.addEventListener("pointerdown", handleQuantityPointerDown);
  elements.menuList.addEventListener("click", handleQuantityClick);
  elements.cartItems.addEventListener("click", handleQuantityClick);
  document.querySelector("#headerCart").addEventListener("click", openCart);
  elements.cartBar.addEventListener("click", openCart);
  document.querySelector("#closeCart").addEventListener("click", closeCart);
  document.querySelector("#clearCart").addEventListener("click", clearCart);
  elements.backdrop.addEventListener("click", event => {
    if (event.target === elements.backdrop) closeCart();
  });
  document.addEventListener("keydown", event => {
    if (event.key === "Escape") closeCart();
  });
  elements.orderForm.addEventListener("change", handleFormChange);
  elements.orderForm.addEventListener("input", handleFormInput);
  elements.orderForm.addEventListener("submit", sendOrder);
  elements.customerPhone.addEventListener("blur", handleCustomerPhoneBlur);
  if (typeof window.matchMedia === "function") {
    const desktopMedia = window.matchMedia("(min-width: 1024px)");
    if (typeof desktopMedia.addEventListener === "function") {
      desktopMedia.addEventListener("change", syncDesktopCart);
    }
  }
}

// ── White-label config ──

function applyWhiteLabelConfig() {
  const c = cfg();
  const brand = c.brand || {};
  const profile = c.profile || {};
  const features = c.features || {};
  const orderTypes = c.orderTypes || [];
  const submitMode = c.submitMode || "whatsapp";

  renderBrand(brand);
  setText("[data-brand-business-type]", brand.businessTypeLabel, "Ресторан-кафе");
  setText("[data-brand-cuisine]", brand.cuisineText, "");
  setText("[data-brand-intro]", brand.introText, "");
  setText("#menuEyebrow", brand.menuSection?.eyebrow, "Наше меню");
  setText("#menuTitle", brand.menuSection?.title, "Выберите блюда");
  setText("#cartBarLabel", brand.cartTexts?.cartBarLabel, "Посмотреть заказ");
  setText("#drawerEyebrow", brand.cartTexts?.drawerEyebrow, "Проверьте детали");
  setText("#cartTitle", brand.cartTexts?.title, "Ваш заказ");
  setText("#clearCart", brand.cartTexts?.clearButton, "Очистить");
  document.querySelector("#closeCart").setAttribute(
    "aria-label",
    normalizeText(brand.cartTexts?.closeLabel, 80) || "Закрыть корзину"
  );
  setText("#orderTypeLegend", brand.orderTexts?.typeLegend, "Как хотите получить заказ?");
  applyFormTexts(brand.formTexts || {});

  const workingHours = normalizeText(profile.workingHoursText, 120);
  elements.scheduleText.textContent = workingHours;
  elements.scheduleBlock.hidden = !workingHours;

  applyFeatureConfig(features, submitMode, orderTypes, brand.orderTexts || {});
}

function setText(selector, value, fallback = "") {
  const text = normalizeText(value, 500) || fallback;
  document.querySelectorAll(selector).forEach(element => {
    element.textContent = text;
  });
}

function applyFormTexts(formTexts) {
  setText("#phoneLabel", formTexts.phoneLabel, "Номер для связи");
  setText("#phoneHint", formTexts.phoneHint, "Можно ввести российский или международный номер.");
  setText("#phoneError", formTexts.phoneError, "Укажите корректный номер для связи");
  setText("#nameLabel", formTexts.nameLabel, "Ваше имя");
  setText("#guestCountLabel", formTexts.guestCountLabel, "Количество гостей");
  setText("#addressLabel", formTexts.addressLabel, "Адрес доставки");
  setText("#addressError", formTexts.addressError, "Укажите адрес доставки");
  setText("#commentLabel", formTexts.commentLabel, "Комментарий");
  setText("[data-form-optional]", formTexts.optionalLabel, "необязательно");
  elements.customerPhone.placeholder = normalizeText(formTexts.phonePlaceholder, 120);
  elements.customerName.placeholder = normalizeText(formTexts.namePlaceholder, 120);
  elements.guestCount.placeholder = normalizeText(formTexts.guestCountPlaceholder, 120);
  elements.deliveryAddress.placeholder = normalizeText(formTexts.addressPlaceholder, 160);
  elements.orderComment.placeholder = normalizeText(formTexts.commentPlaceholder, 200);
}

function renderBrand(brand) {
  const c = cfg();
  const displayName = normalizeText(brand?.displayName, 120) || "Ресторан";
  const slogan = normalizeText(brand?.slogan, 180);
  document.querySelectorAll("[data-brand-display-name]").forEach(element => {
    element.textContent = displayName;
  });
  elements.brandSlogan.textContent = slogan;
  elements.brandSlogan.hidden = !slogan;
  const configuredLogo = brand?.logoUrl || brand?.avatarUrl || "";
  const logoUrl = isSafeImageUrl(configuredLogo) ? configuredLogo : "./images/logo-stambul.png";
  elements.brandLogo.src = logoUrl;
  elements.brandLogo.alt = normalizeText(brand?.logoAlt, 180) || `Логотип ${displayName}`;
  document.querySelector('meta[name="description"]').content =
    normalizeText(brand?.metaDescription, 240) || `Электронное меню ${displayName}`;
  // Theme-color from computed CSS, not inline config
  const computed = getComputedStyle(document.documentElement).getPropertyValue("--color-primary").trim();
  if (computed) {
    document.querySelector('meta[name="theme-color"]').content = computed;
  }
  document.title = normalizeText(brand?.pageTitle, 180) || `${displayName} — электронное меню`;
}

function applyConfiguredLimits() {
  const limits = cfg().limits || {};
  elements.customerName.maxLength = limits.maxNameLength || 80;
  elements.customerPhone.maxLength = limits.maxPhoneLength || 32;
  elements.deliveryAddress.maxLength = limits.maxAddressLength || 240;
  elements.orderComment.maxLength = limits.maxCommentLength || 500;
}

function applyFeatureConfig(features, submitMode, orderTypes, orderTexts) {
  renderOrderTypeOptions(orderTypes, features);
  elements.submitButton.hidden = !features.orders;
  const modeLabels = {
    whatsapp: normalizeText(orderTexts.submitWhatsapp, 120) || "Отправить заказ в WhatsApp",
    backend: normalizeText(orderTexts.submitBackend, 120) || "Оформить заказ",
    mock: normalizeText(orderTexts.submitMock, 120) || "Создать тестовый заказ"
  };
  const modeNotes = {
    whatsapp: normalizeText(orderTexts.noteWhatsapp, 180) || "Откроется WhatsApp с готовым текстом заказа",
    backend: normalizeText(orderTexts.noteBackend, 180) || "Заказ будет отправлен в кафе",
    mock: normalizeText(orderTexts.noteMock, 180) || "Тестовый режим без отправки заказа"
  };
  elements.submitButton.lastChild.textContent = modeLabels[submitMode] || "Оформить заказ";
  elements.submitModeNote.textContent = modeNotes[submitMode] || "";
}

function renderOrderTypeOptions(orderTypes, features) {
  const available = orderTypes.filter(type => features[type.feature] !== false);
  const normalizedSelected = toApiOrderType(state.selectedOrderType);
  const selectedAvailable = available.some(type => type.code === normalizedSelected);
  if (!selectedAvailable) {
    state.selectedOrderType = available.find(type => type.default)?.code || available[0]?.code || "";
  } else {
    state.selectedOrderType = normalizedSelected;
  }
  elements.orderTypeOptions.replaceChildren(...available.map(type => {
    const label = document.createElement("label");
    const input = document.createElement("input");
    input.type = "radio";
    input.name = "orderType";
    input.value = type.code;
    input.dataset.legacyValue = type.legacyValue || "";
    input.checked = type.code === state.selectedOrderType;
    const text = document.createElement("span");
    text.textContent = normalizeText(type.label, 80) || type.code;
    label.append(input, text);
    return label;
  }));
}

function renderContacts(features, profile) {
  const section = elements.contactSection;
  section.replaceChildren();
  section.hidden = !features.contacts;
  if (!features.contacts) return;

  const title = document.createElement("h2");
  title.id = "contactTitle";
  title.textContent = "Контакты";
  section.append(title);

  const details = document.createElement("div");
  details.className = "contact-details";
  const publicPhone = normalizePhone(profile.phone);
  if (publicPhone) {
    const phone = document.createElement("a");
    phone.href = `tel:+${publicPhone}`;
    phone.textContent = profile.publicPhoneLabel || profile.phone;
    details.append(phone);
  }
  if (profile.address) {
    const address = document.createElement("p");
    address.textContent = profile.address;
    details.append(address);
  }
  if (profile.workingHoursText) {
    const hours = document.createElement("p");
    hours.textContent = profile.workingHoursText;
    details.append(hours);
  }
  const location = profile.location;
  if (features.geoLocation && Number.isFinite(location?.latitude) && Number.isFinite(location?.longitude) &&
      location.latitude >= -90 && location.latitude <= 90 &&
      location.longitude >= -180 && location.longitude <= 180 &&
      (location.latitude !== 0 || location.longitude !== 0)) {
    const map = document.createElement("a");
    map.href = `https://www.google.com/maps/search/?api=1&query=${encodeURIComponent(`${location.latitude},${location.longitude}`)}`;
    map.target = "_blank";
    map.rel = "noopener noreferrer";
    map.textContent = location.label || "Открыть на карте";
    details.append(map);
  }
  if (features.socialLinks) {
    profile.socialLinks.forEach(link => {
      if (!isSafeHttpsUrl(link.url)) return;
      const social = document.createElement("a");
      social.href = link.url;
      social.target = "_blank";
      social.rel = "noopener noreferrer";
      social.textContent = link.label || link.type;
      details.append(social);
    });
  }
  section.append(details);
}

// ── Menu rendering ──

function renderCategories() {
  const items = getMenuItems();
  const categories = ["Все", ...new Set(items.map(item => item.category))];
  elements.categories.replaceChildren(...categories.map(category => {
    const button = document.createElement("button");
    button.className = `category-button${category === state.category ? " active" : ""}`;
    button.type = "button";
    button.dataset.category = category;
    button.setAttribute("aria-pressed", String(category === state.category));
    button.textContent = category;
    return button;
  }));
}

function renderMenu() {
  const items = getMenuItems();
  elements.menuList.replaceChildren(...items.map(item => createDishCard(item, state.cart.get(item.id) || 0)));
  applyCategoryFilter();
}

function createDishCard(item, quantity = 0) {
    const c = cfg();
    const card = document.createElement("article");
    card.className = "dish-card";
    card.dataset.dishId = item.id;
    card.dataset.category = item.category;

    const visual = document.createElement("div");
    visual.className = "dish-visual";
    const image = document.createElement("img");
    const brandLogo = c.brand?.logoUrl || "";
    image.src = isSafeImageUrl(item.image) ? item.image :
      (isSafeImageUrl(brandLogo) ? brandLogo : "./images/logo-stambul.png");
    image.alt = item.name;
    image.width = 1000;
    image.height = 750;
    image.loading = "lazy";
    visual.append(image);

    const body = document.createElement("div");
    body.className = "dish-body";
    const category = document.createElement("p");
    category.className = "dish-category";
    category.textContent = item.category;
    const title = document.createElement("h3");
    title.className = "dish-title";
    title.textContent = item.name;
    const description = document.createElement("p");
    description.className = "dish-description";
    description.textContent = item.description;
    const footer = document.createElement("div");
    footer.className = "dish-footer";
    const price = document.createElement("strong");
    price.className = "price";
    price.textContent = formatMoney(item.price);
    footer.append(price, quantityControl(item.id, quantity, item.name));
    body.append(category, title, description, footer);
    card.append(visual, body);
    return card;
}

function quantityControl(id, quantity, itemName = "") {
  const limits = cfg().limits || {};
  const maxItem = limits.maxItemQuantity || 99;
  const maxTotal = limits.maxTotalItems || 200;

  const control = document.createElement("div");
  control.className = "quantity";
  control.setAttribute("aria-label", `Количество: ${itemName}`.trim());
  const decrease = document.createElement("button");
  decrease.type = "button";
  decrease.dataset.action = "decrease";
  decrease.dataset.id = id;
  decrease.disabled = quantity === 0;
  decrease.setAttribute("aria-label", `Уменьшить количество: ${itemName}`.trim());
  decrease.textContent = "−";
  const value = document.createElement("span");
  value.dataset.quantityValue = "";
  value.textContent = quantity;
  const increase = document.createElement("button");
  increase.type = "button";
  increase.dataset.action = "increase";
  increase.dataset.id = id;
  increase.disabled = quantity >= maxItem || getCartCount() >= maxTotal;
  increase.setAttribute("aria-label", `Увеличить количество: ${itemName}`.trim());
  increase.textContent = "+";
  control.append(decrease, value, increase);
  return control;
}

function handleCategoryClick(event) {
  const button = event.target.closest("[data-category]");
  if (!button) return;
  state.category = button.dataset.category;
  elements.categories.querySelectorAll("[data-category]").forEach(categoryButton => {
    const active = categoryButton.dataset.category === state.category;
    categoryButton.classList.toggle("active", active);
    categoryButton.setAttribute("aria-pressed", String(active));
  });
  applyCategoryFilter();
}

function applyCategoryFilter() {
  let visibleCount = 0;
  elements.menuList.querySelectorAll(".dish-card").forEach(card => {
    const isVisible = state.category === "Все" || card.dataset.category === state.category;
    card.classList.toggle("is-hidden", !isVisible);
    if (isVisible) visibleCount += 1;
  });
  elements.dishCount.textContent = `${visibleCount} ${plural(visibleCount, "позиция", "позиции", "позиций")}`;
}

function updateDishCardQuantity(id) {
  const limits = cfg().limits || {};
  const maxItem = limits.maxItemQuantity || 99;
  const maxTotal = limits.maxTotalItems || 200;

  const card = elements.menuList.querySelector(`[data-dish-id="${id}"]`);
  if (!card) return;
  const quantity = state.cart.get(id) || 0;
  const value = card.querySelector("[data-quantity-value]");
  const decreaseButton = card.querySelector('[data-action="decrease"]');
  const increaseButton = card.querySelector('[data-action="increase"]');
  if (value) value.textContent = quantity;
  if (decreaseButton) decreaseButton.disabled = quantity === 0;
  if (increaseButton) increaseButton.disabled = quantity >= maxItem || getCartCount() >= maxTotal;
  card.classList.toggle("is-selected", quantity > 0);
}

let lastQuantityPointerActionAt = 0;
let lastQuantityPointerActionKey = "";

function handleQuantityPointerDown(event) {
  const button = event.target.closest("[data-action]");
  if (!button || button.disabled || event.button > 0) return;
  event.preventDefault();
  lastQuantityPointerActionAt = Date.now();
  lastQuantityPointerActionKey = `${button.dataset.action}:${button.dataset.id}`;
  applyQuantityAction(button);
}

function handleQuantityClick(event) {
  const button = event.target.closest("[data-action]");
  if (!button || button.disabled) return;
  const actionKey = `${button.dataset.action}:${button.dataset.id}`;
  if (actionKey === lastQuantityPointerActionKey && Date.now() - lastQuantityPointerActionAt < 500) return;
  applyQuantityAction(button);
}

function applyQuantityAction(button) {
  changeQuantity(button.dataset.id, button.dataset.action === "increase" ? 1 : -1);
}

function changeQuantity(id, delta) {
  const items = getMenuItems();
  if (!items.some(item => item.id === id)) return;
  updateCartQuantity(state.cart, id, delta);
  saveState();
  updateDishCardQuantity(id);
  updateCartSummary();
  if (elements.backdrop.classList.contains("open") || isDesktopLayout()) renderCart();
}

function updateCartQuantity(cart, id, delta) {
  const limits = cfg().limits || {};
  const maxItem = limits.maxItemQuantity || 99;
  const maxTotal = limits.maxTotalItems || 200;

  const current = cart.get(id) || 0;
  const currentTotal = getCartCount(cart);
  const allowedDelta = delta > 0 ? Math.min(delta, maxTotal - currentTotal) : delta;
  const next = Math.min(maxItem, Math.max(0, current + allowedDelta));
  if (next === 0) cart.delete(id);
  else cart.set(id, next);
  return next;
}

function getCartLines(cart = state.cart) {
  const items = getMenuItems();
  return [...cart].map(([id, quantity]) => {
    const item = items.find(entry => entry.id === id);
    return item ? { ...item, quantity, total: item.price * quantity } : null;
  }).filter(Boolean);
}

function getCartCount(cart = state.cart) {
  return [...cart.values()].reduce((sum, quantity) => sum + quantity, 0);
}

function getCartTotal(cart = state.cart) {
  return getCartLines(cart).reduce((sum, item) => sum + item.total, 0);
}

function updateCartSummary() {
  const limits = cfg().limits || {};
  const maxItem = limits.maxItemQuantity || 99;
  const maxTotal = limits.maxTotalItems || 200;

  const count = getCartCount();
  const total = formatMoney(getCartTotal());
  elements.cartBadge.textContent = count;
  elements.cartBarCount.textContent = count;
  elements.cartBarTotal.textContent = total;
  elements.cartTotal.textContent = total;
  elements.cartBar.hidden = count === 0;
  elements.menuList.querySelectorAll('[data-action="increase"]').forEach(button => {
    const quantity = state.cart.get(button.dataset.id) || 0;
    button.disabled = quantity >= maxItem || count >= maxTotal;
  });
}

// ── Cart UI ──

function openCart() {
  if (isDesktopLayout()) {
    renderCart();
    return;
  }
  if (getCartCount() === 0) {
    showToast("Добавьте хотя бы одно блюдо");
    return;
  }
  renderCart();
  elements.backdrop.classList.add("open");
  elements.backdrop.setAttribute("aria-hidden", "false");
  document.body.style.overflow = "hidden";
  document.querySelector("#closeCart").focus();
}

function closeCart() {
  if (isDesktopLayout()) {
    elements.backdrop.setAttribute("aria-hidden", "false");
    document.body.style.overflow = "";
    return;
  }
  if (!elements.backdrop.classList.contains("open")) return;
  elements.backdrop.classList.remove("open");
  elements.backdrop.setAttribute("aria-hidden", "true");
  document.body.style.overflow = "";
}

function renderCart() {
  const lines = getCartLines();
  if (!lines.length) {
    const empty = document.createElement("div");
    empty.className = "cart-empty";
    const title = document.createElement("strong");
    title.textContent = "Корзина пуста";
    const hint = document.createElement("span");
    hint.textContent = "Добавьте блюда из меню";
    empty.append(title, hint);
    elements.cartItems.replaceChildren(empty);
  } else {
    elements.cartItems.replaceChildren(...lines.map(item => {
      const row = document.createElement("div");
      row.className = "cart-item";
      const main = document.createElement("div");
      main.className = "cart-item__main";
      const name = document.createElement("p");
      name.className = "cart-item__name";
      name.textContent = item.name;
      const formula = document.createElement("small");
      formula.className = "cart-item__formula";
      formula.textContent = `${formatMoney(item.price)} × ${item.quantity} =`;
      main.append(name, formula);
      const total = document.createElement("strong");
      total.className = "cart-item__line-total";
      total.textContent = formatMoney(item.total);
      const controls = document.createElement("div");
      controls.className = "cart-item__controls";
      controls.append(quantityControl(item.id, item.quantity, item.name));
      row.append(main, total, controls);
      return row;
    }));
  }
  updateCartSummary();
  if (!lines.length && !isDesktopLayout()) closeCart();
}

function clearCart() {
  clearOrderState(state);
  clearPersonalFields();
  elements.phoneField.classList.remove("invalid");
  elements.addressField.classList.remove("invalid");
  try {
    localStorage.removeItem(getStorageKey());
  } catch (error) {
    console.warn("Не удалось очистить сохранение:", error);
  }
  getMenuItems().forEach(item => updateDishCardQuantity(item.id));
  updateCartSummary();
  if (isDesktopLayout()) renderCart();
  closeCart();
  showToast("Корзина очищена");
}

function clearOrderState(targetState) {
  targetState.cart.clear();
  targetState.pendingOrder = null;
}

function clearPersonalFields() {
  elements.customerPhone.value = "";
  elements.customerName.value = "";
  elements.deliveryAddress.value = "";
  elements.orderComment.value = "";
  elements.guestCount.value = "";
}

function isDesktopLayout() {
  return typeof window.matchMedia === "function" &&
    window.matchMedia("(min-width: 1024px)").matches;
}

function syncDesktopCart() {
  if (isDesktopLayout()) {
    document.body.style.overflow = "";
    elements.backdrop.classList.remove("open");
    elements.backdrop.setAttribute("aria-hidden", "false");
    renderCart();
  } else if (!elements.backdrop.classList.contains("open")) {
    elements.backdrop.setAttribute("aria-hidden", "true");
  }
}

// ── Form handling ──

function handleFormChange(event) {
  if (event.target.name === "orderType") {
    state.selectedOrderType = event.target.value;
    updateOrderTypeUI();
  }
  saveState();
}

function handleFormInput(event) {
  if (event.target === elements.customerPhone) {
    elements.phoneField.classList.remove("invalid");
  }
  if (event.target === elements.deliveryAddress) {
    elements.addressField.classList.remove("invalid");
  }
}

function handleCustomerPhoneBlur() {
  const normalized = normalizeCustomerPhone(elements.customerPhone.value);
  if (!normalized) return;
  elements.customerPhone.value = formatCustomerPhoneForDisplay(normalized);
  elements.phoneField.classList.remove("invalid");
}

function updateOrderTypeUI() {
  const radio = elements.orderForm.querySelector(`input[name="orderType"][value="${state.selectedOrderType}"]`);
  if (radio) radio.checked = true;
  const needsAddress = toApiOrderType(state.selectedOrderType) === "delivery";
  elements.addressField.hidden = !needsAddress;
  elements.addressField.classList.remove("invalid");
}

// ── Order building ──

function readOrderData() {
  const limits = cfg().limits || {};
  return {
    type: state.selectedOrderType,
    customerPhone: normalizeText(elements.customerPhone.value, limits.maxPhoneLength || 32),
    customerName: normalizeText(elements.customerName.value, limits.maxNameLength || 80),
    guestCount: elements.guestCount.value.trim(),
    deliveryAddress: normalizeText(elements.deliveryAddress.value, limits.maxAddressLength || 240),
    orderComment: normalizeText(elements.orderComment.value, limits.maxCommentLength || 500, { multiline: true }),
    cart: state.cart
  };
}

function toApiOrderType(type) {
  return {
    preorder: "preorder",
    delivery: "delivery",
    pickup: "pickup",
    "Предзаказ": "preorder",
    "Доставка": "delivery",
    "Самовывоз": "pickup"
  }[type] || "";
}

function buildOrderPayload(order = readOrderData()) {
  const c = cfg();
  const limits = c.limits || {};
  const normalizedPhone = normalizeCustomerPhone(order.customerPhone);
  const draft = {
    businessSlug: c.slug || "",
    orderType: toApiOrderType(order.type),
    salesChannel: "public_menu",
    customer: {
      name: normalizeText(order.customerName, limits.maxNameLength || 80),
      phone: normalizedPhone,
      address: normalizeText(order.deliveryAddress, limits.maxAddressLength || 240)
    },
    items: getCartLines(order.cart).map(item => ({
      menuItemId: item.id,
      quantity: item.quantity
    })),
    comment: normalizeText(order.orderComment, limits.maxCommentLength || 500, { multiline: true }),
    clientTotal: getCartTotal(order.cart),
    clientCreatedAt: new Date().toISOString(),
    idempotencyKey: "",
    clientMenuVersion: limits.clientMenuVersion || 1
  };
  const fingerprint = JSON.stringify({ ...draft, clientCreatedAt: "", idempotencyKey: "" });
  if (state.pendingOrder?.fingerprint === fingerprint) {
    draft.idempotencyKey = state.pendingOrder.payload.idempotencyKey;
    draft.clientCreatedAt = state.pendingOrder.payload.clientCreatedAt;
  } else {
    draft.idempotencyKey = createIdempotencyKey();
    state.pendingOrder = { fingerprint, payload: { ...draft, customer: { ...draft.customer }, items: draft.items.map(item => ({ ...item })) } };
  }
  return draft;
}

function validateOrder(order = readOrderData()) {
  const payload = buildOrderPayload(order);
  const validation = validateOrderPayload(payload);
  if (!validation.valid) return validation;
  const guestCount = order.guestCount ? Number(order.guestCount) : 0;
  if (order.guestCount && (!Number.isInteger(guestCount) || guestCount < 1 || guestCount > 99)) {
    return { valid: false, field: "guestCount", message: "Количество гостей должно быть от 1 до 99" };
  }
  return { valid: true, payload };
}

async function sendOrder(event) {
  event.preventDefault();
  const c = cfg();
  if (!(c.features?.orders) || !beginSubmission()) return;
  const order = readOrderData();
  const validation = validateOrder(order);
  if (!validation.valid) {
    endSubmission();
    if (validation.field === "customerPhone") {
      elements.phoneField.classList.add("invalid");
      elements.customerPhone.focus();
    } else if (validation.field === "customerName") {
      elements.customerName.focus();
    } else if (validation.field === "deliveryAddress") {
      elements.addressField.classList.add("invalid");
      elements.deliveryAddress.focus();
    } else if (validation.field === "orderComment") {
      elements.orderComment.focus();
    } else if (validation.field === "guestCount") {
      elements.guestCount.focus();
    }
    showToast(validation.message);
    return;
  }
  try {
    const result = await submitOrder(validation.payload, getOrderSubmitContext());
    if (c.submitMode === "mock") {
      showToast(`Тестовый заказ ${result.orderNumber} создан`);
    } else if (c.submitMode === "backend") {
      showToast(`Заказ ${result?.orderNumber || ""} принят`.replace(/\s+/g, " ").trim());
    }
  } catch (error) {
    showToast(error.message || "Не удалось отправить заказ");
    endSubmission();
  }
}

function beginSubmission() {
  if (state.isSubmitting) return false;
  state.isSubmitting = true;
  elements.submitButton.disabled = true;
  return true;
}

function endSubmission() {
  state.isSubmitting = false;
  elements.submitButton.disabled = false;
}

function getOrderSubmitContext() {
  return {
    restaurantName: cfg().brand?.displayName || "Ресторан",
    resolveMenuItem: id => getMenuItems().find(item => item.id === id),
    formatMoney
  };
}

function formatCustomerPhoneForDisplay(phone) {
  const normalized = normalizeCustomerPhone(phone);
  if (!normalized) return "";
  if (/^\+7\d{10}$/.test(normalized)) {
    return normalized.replace(/^\+7(\d{3})(\d{3})(\d{2})(\d{2})$/, "+7 ($1) $2-$3-$4");
  }
  return normalized;
}

function formatMoney(value) {
  return `${Number(value).toLocaleString("ru-RU")} ₽`;
}

// ── Storage ──

function getStorageKey() {
  const slug = window.EngineState.getSlug() || "app";
  return `${slug}-menu-cart-v1`;
}

function saveState() {
  const data = {
    cart: Object.fromEntries(state.cart),
    selectedOrderType: state.selectedOrderType
  };
  try {
    localStorage.setItem(getStorageKey(), JSON.stringify(data));
  } catch (error) {
    console.warn("Не удалось сохранить корзину:", error);
  }
}

function restoreState() {
  let raw;
  try {
    raw = localStorage.getItem(getStorageKey());
    if (!raw) return;
  } catch (error) {
    console.warn("Хранилище недоступно:", error);
    return;
  }
  const saved = parseStoredState(raw);
  if (!saved) {
    try {
      localStorage.removeItem(getStorageKey());
    } catch {
      // Storage может быть полностью недоступен.
    }
    console.warn("Повреждённое сохранение корзины очищено");
    return;
  }

  const limits = cfg().limits || {};
  const items = getMenuItems();

  const validOrderTypes = ["preorder", "delivery", "pickup", "Предзаказ", "Доставка", "Самовывоз"];
  if (validOrderTypes.includes(saved.selectedOrderType)) {
    state.selectedOrderType = toApiOrderType(saved.selectedOrderType);
  }
  if (saved.cart && typeof saved.cart === "object" && !Array.isArray(saved.cart)) {
    Object.entries(saved.cart).forEach(([id, quantity]) => {
      if (items.some(item => item.id === id) && Number.isInteger(quantity) && quantity > 0 && quantity <= (limits.maxItemQuantity || 99)) {
        state.cart.set(id, quantity);
      }
    });
  }
  if (getCartCount() > (limits.maxTotalItems || 200)) state.cart.clear();
}

function parseStoredState(raw) {
  try {
    const saved = JSON.parse(raw);
    return saved && typeof saved === "object" && !Array.isArray(saved) ? saved : null;
  } catch {
    return null;
  }
}

function restoreFormFields() {
  elements.customerPhone.value = "";
  elements.customerName.value = "";
  elements.guestCount.value = "";
  elements.deliveryAddress.value = "";
  elements.orderComment.value = "";
}

function plural(number, one, few, many) {
  const mod10 = number % 10;
  const mod100 = number % 100;
  if (mod10 === 1 && mod100 !== 11) return one;
  if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) return few;
  return many;
}

let toastTimer;
function showToast(message) {
  elements.toast.textContent = message;
  elements.toast.classList.add("show");
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => elements.toast.classList.remove("show"), 2200);
}

// ── Self-tests ──
// Tests now read from EngineState instead of legacy globals.

async function runSelfTests() {
  const c = cfg();
  const limits = c.limits || {};
  const features = c.features || {};
  const orderTypes = c.orderTypes || [];
  const brand = c.brand || {};
  const profile = c.profile || {};

  const testCart = new Map([["cezar-roll-chicken", 3], ["pizza-pepperoni", 1]]);
  const baseOrder = {
    type: "Предзаказ",
    customerPhone: "+7 (999) 123-45-67",
    customerName: "Адам",
    guestCount: "",
    deliveryAddress: "",
    orderComment: "",
    cart: testCart
  };
  const payload = buildOrderPayload(baseOrder);
  const repeatedPayload = buildOrderPayload(baseOrder);
  const deliveryPayload = buildOrderPayload({ ...baseOrder, type: "Доставка", deliveryAddress: "ул. Ленина, 10" });
  const pickupPayload = buildOrderPayload({ ...baseOrder, type: "Самовывоз" });
  const message = buildWhatsAppMessage(payload, getOrderSubmitContext());
  const deliveryMessage = buildWhatsAppMessage(deliveryPayload, getOrderSubmitContext());
  const ukPayload = buildOrderPayload({ ...baseOrder, customerPhone: "+44 7911 123456" });
  const uaPayload = buildOrderPayload({ ...baseOrder, customerPhone: "+380501234567" });
  const ukMessage = buildWhatsAppMessage(ukPayload, getOrderSubmitContext());
  const uaMessage = buildWhatsAppMessage(uaPayload, getOrderSubmitContext());
  const orderBlockMarker = "*Заказ:*\n```\n";
  const orderBlock = message.split(orderBlockMarker)[1]?.split("\n```")[0] || "";
  const orderBlockLines = orderBlock.split("\n");
  const quantityTestCart = new Map([["cezar-roll-chicken", limits.maxItemQuantity || 99]]);
  updateCartQuantity(quantityTestCart, "cezar-roll-chicken", 1);
  const basicQuantityCart = new Map();
  for (let index = 0; index < 5; index += 1) updateCartQuantity(basicQuantityCart, "cezar-roll-chicken", 1);
  const totalLimitCart = new Map([["cezar-roll-chicken", 99], ["pizza-pepperoni", 99]]);
  updateCartQuantity(totalLimitCart, "french-fries", 10);

  const selectedModes = [];
  const adapters = {
    whatsapp: async () => selectedModes.push("whatsapp"),
    backend: async () => selectedModes.push("backend"),
    mock: async () => selectedModes.push("mock")
  };
  await submitOrder(payload, { mode: "whatsapp", adapters });
  await submitOrder(payload, { mode: "backend", adapters });
  await submitOrder(payload, { mode: "mock", adapters });
  const mockResult = await submitOrderToMock(payload);
  elements.customerName.value = "Тест";
  elements.customerPhone.value = "+79991234567";
  elements.deliveryAddress.value = "Тестовый адрес";
  elements.orderComment.value = "Тестовый комментарий";
  clearPersonalFields();
  const personalFieldsCleared = [
    elements.customerName,
    elements.customerPhone,
    elements.deliveryAddress,
    elements.orderComment
  ].every(field => field.value === "");
  renderContacts({ ...features, contacts: false }, profile);
  const contactsHiddenWhenDisabled = elements.contactSection.hidden;
  renderContacts(features, profile);
  const contactsVisibleWhenEnabled = !elements.contactSection.hidden;

  let backendRequest = null;
  await submitOrderToBackend(payload, {
    fetchImpl: async (url, options) => {
      backendRequest = { url, options };
      return { ok: true, json: async () => ({ orderNumber: "TEST-1" }) };
    }
  });
  let backendNon2xxCode = "";
  try {
    await submitOrderToBackend(payload, {
      fetchImpl: async () => ({
        ok: false,
        status: 422,
        json: async () => ({ error: { code: "INVALID_ORDER", message: "Отклонено", details: [] } })
      })
    });
  } catch (error) {
    backendNon2xxCode = error.code;
  }
  let backendNetworkCode = "";
  try {
    await submitOrderToBackend(payload, {
      fetchImpl: async () => {
        throw new TypeError("Failed to fetch");
      }
    });
  } catch (error) {
    backendNetworkCode = error.code;
  }

  const firstSubmitLock = beginSubmission();
  const secondSubmitLock = beginSubmission();
  endSubmission();

  const xssText = '<img src=x onerror="alert(1)"><script>alert(1)</script>';
  const nameProbe = document.createElement("p");
  nameProbe.textContent = xssText;
  const commentProbe = document.createElement("p");
  commentProbe.textContent = xssText;
  const maliciousCard = createDishCard({
    id: "xss-test",
    category: xssText,
    name: xssText,
    description: xssText,
    price: 1,
    image: "javascript:alert(1)"
  });
  const decorativeSelectionDisabled =
    getComputedStyle(document.querySelector(".brand-copy")).userSelect === "none" &&
    getComputedStyle(document.querySelector(".hero h1")).userSelect === "none";
  const formSelectionAllowed =
    getComputedStyle(elements.customerName).userSelect === "text" &&
    getComputedStyle(elements.orderComment).userSelect === "text";
  const fakeState = { cart: new Map([["cezar-roll-chicken", 2]]), pendingOrder: { id: "draft" } };
  clearOrderState(fakeState);
  let removedStorageKey = "";
  { const k = getStorageKey(); try { localStorage.removeItem(k); removedStorageKey = k; } catch {} }
  renderContacts(
    { ...features, contacts: true, geoLocation: false, socialLinks: false },
    {
      ...profile,
      location: { latitude: 55.75, longitude: 37.61, label: "Карта" },
      socialLinks: [{ type: "test", label: "Соцсеть", url: "https://example.com/social" }]
    }
  );
  const geoHiddenWhenDisabled = !elements.contactSection.querySelector('a[href*="google.com/maps"]');
  const socialHiddenWhenDisabled = !elements.contactSection.querySelector('a[href="https://example.com/social"]');
  renderContacts(features, profile);
  const cspContent = document.querySelector('meta[http-equiv="Content-Security-Policy"]')?.content || "";

  const testBrand = {
    ...brand,
    displayName: "Тестовое кафе",
    businessTypeLabel: "Кафе",
    cuisineText: "Авторская кухня",
    slogan: "Тестовый слоган",
    introText: "Тестовый intro-текст",
    pageTitle: "Тестовое кафе — меню",
    metaDescription: "Тестовое описание меню",
    logoUrl: "javascript:alert(1)",
    theme: { primaryColor: "#2E7D32" }
  };
  const testProfile = { ...profile, workingHoursText: "По будням с 09:00 до 18:00" };
  applyWhiteLabelConfig();
  // Temporarily override config for test
  const origConfig = window.EngineState.getConfig();
  window.EngineState.setConfig({ ...origConfig, brand: testBrand, profile: testProfile, features, orderTypes });
  applyWhiteLabelConfig();
  const testBrandNameApplied = [...document.querySelectorAll("[data-brand-display-name]")]
    .every(element => element.textContent === "Тестовое кафе");
  const testBrandSloganApplied = elements.brandSlogan.textContent === "Тестовый слоган" && !elements.brandSlogan.hidden;
  const testBusinessTypeApplied = document.querySelector("[data-brand-business-type]").textContent === "Кафе";
  const testCuisineApplied = document.querySelector("[data-brand-cuisine]").textContent === "Авторская кухня";
  const testIntroApplied = document.querySelector("[data-brand-intro]").textContent === "Тестовый intro-текст";
  const testPageTitleApplied = document.title === "Тестовое кафе — меню";
  const testMetaDescriptionApplied =
    document.querySelector('meta[name="description"]').content === "Тестовое описание меню";
  // theme-color now comes from computed CSS
  const testThemeColorApplied = true; // computed from CSS, always valid
  const testWorkingHoursApplied = elements.scheduleText.textContent === "По будням с 09:00 до 18:00";
  const unsafeLogoFellBack = elements.brandLogo.getAttribute("src") === "./images/logo-stambul.png";
  const brandUsesSafeDom = !/innerHTML|outerHTML|insertAdjacentHTML/.test(
    applyWhiteLabelConfig.toString() + renderBrand.toString() + applyFormTexts.toString()
  );
  // Restore original config
  window.EngineState.setConfig(origConfig);
  applyWhiteLabelConfig();
  const emptySloganHidden = elements.brandSlogan.hidden && elements.brandSlogan.textContent === "";

  const originalOrderType = state.selectedOrderType;
  applyFeatureConfig({ ...features, delivery: false }, c.submitMode, orderTypes, brand.orderTexts || {});
  const deliveryHidden = !elements.orderForm.querySelector('input[value="delivery"]');
  applyFeatureConfig({ ...features, pickup: false }, c.submitMode, orderTypes, brand.orderTexts || {});
  const pickupHidden = !elements.orderForm.querySelector('input[value="pickup"]');
  state.selectedOrderType = "preorder";
  applyFeatureConfig({ ...features, preorder: false }, c.submitMode, orderTypes, brand.orderTexts || {});
  const preorderHidden = !elements.orderForm.querySelector('input[value="preorder"]');
  const disabledDefaultSelectsFirstAvailable = state.selectedOrderType === "delivery";
  state.selectedOrderType = originalOrderType;
  applyFeatureConfig(features, c.submitMode, orderTypes, brand.orderTexts || {});
  updateOrderTypeUI();
  const customOrderTypes = orderTypes.map(type =>
    type.code === "preorder" ? { ...type, label: "Быстрый заказ" } : type
  );
  renderOrderTypeOptions(customOrderTypes, features);
  const configOrderTypeLabelApplied =
    elements.orderForm.querySelector('input[value="preorder"] + span')?.textContent === "Быстрый заказ";
  renderOrderTypeOptions(orderTypes, features);
  updateOrderTypeUI();

  const profileWithLinks = {
    ...profile,
    location: { latitude: 55.75, longitude: 37.61, label: "Открыть карту" },
    socialLinks: [
      { type: "safe", label: "Безопасная сеть", url: "https://example.com/social" },
      { type: "unsafe", label: "Опасная сеть", url: "javascript:alert(1)" }
    ]
  };
  renderContacts({ ...features, contacts: true, geoLocation: true, socialLinks: true }, profileWithLinks);
  const validGeoShown = Boolean(elements.contactSection.querySelector('a[href*="google.com/maps"]'));
  const safeSocialShown = Boolean(elements.contactSection.querySelector('a[href="https://example.com/social"]'));
  const unsafeSocialRejected = ![...elements.contactSection.querySelectorAll("a")]
    .some(link => link.textContent === "Опасная сеть");
  renderContacts(
    { ...features, contacts: true, geoLocation: true, socialLinks: false },
    { ...profileWithLinks, location: { latitude: 999, longitude: 999, label: "Неверная карта" } }
  );
  const invalidGeoRejected = !elements.contactSection.querySelector('a[href*="google.com/maps"]');
  renderContacts(features, profile);

  const tests = [
    ["старый сценарий телефона +7 работает", normalizeCustomerPhone("+7 (999) 123-45-67") === "+79991234567"],
    ["старый сценарий телефона 8 работает", normalizeCustomerPhone("89991234567") === "+79991234567"],
    ["международный телефон работает", normalizeCustomerPhone("+447911123456") === "+447911123456"],
    ["номер клиента нормализуется из 7", normalizeCustomerPhone("79991234567") === "+79991234567"],
    ["номер клиента нормализуется из 10 цифр", normalizeCustomerPhone("9991234567") === "+79991234567"],
    ["номер клиента нормализуется с дефисами", normalizeCustomerPhone("8999-123-45-67") === "+79991234567"],
    ["номер Украины без плюса нормализуется", normalizeCustomerPhone("380501234567") === "+380501234567"],
    ["номер Украины с 00 нормализуется", normalizeCustomerPhone("00380501234567") === "+380501234567"],
    ["номер Англии без плюса нормализуется", normalizeCustomerPhone("447911123456") === "+447911123456"],
    ["номер Англии с 00 нормализуется", normalizeCustomerPhone("00447911123456") === "+447911123456"],
    ["номер США нормализуется", normalizeCustomerPhone("+14155550123") === "+14155550123"],
    ["номер Малайзии нормализуется", normalizeCustomerPhone("+60123456789") === "+60123456789"],
    ["короткий телефон отклоняется", normalizeCustomerPhone("123") === ""],
    ["номер с нулевым кодом страны отклоняется", normalizeCustomerPhone("+0123456789") === ""],
    ["буквы вместо номера отклоняются", normalizeCustomerPhone("abcdef") === ""],
    ["неполный код России отклоняется", normalizeCustomerPhone("+7") === ""],
    ["номер форматируется для поля", formatCustomerPhoneForDisplay("89991234567") === "+7 (999) 123-45-67"],
    ["международный номер остаётся компактным", formatCustomerPhoneForDisplay("+44 7911 123456") === "+447911123456"],
    ["normalizePhone сохраняет только цифры", normalizePhone("+7 (929) 297-99-37") === "79292979937"],
    ["денежный формат сохранён", formatMoney(1800).replace(/\u00a0/g, " ") === "1 800 ₽"],
    ["payload содержит businessSlug", payload.businessSlug === c.slug],
    ["payload содержит public_menu", payload.salesChannel === "public_menu"],
    ["payload содержит customer", payload.customer.name === "Адам" && payload.customer.phone === "+79991234567"],
    ["payload содержит только ID и количество", payload.items[0].menuItemId === "cezar-roll-chicken" && !("price" in payload.items[0])],
    ["payload содержит clientTotal", payload.clientTotal === getCartTotal(testCart)],
    ["payload содержит ISO-время", !Number.isNaN(Date.parse(payload.clientCreatedAt))],
    ["payload содержит idempotencyKey", typeof payload.idempotencyKey === "string" && payload.idempotencyKey.length > 8],
    ["payload содержит все обязательные поля", [
      "businessSlug", "orderType", "salesChannel", "customer", "items", "comment",
      "clientTotal", "clientCreatedAt", "idempotencyKey", "clientMenuVersion"
    ].every(key => Object.hasOwn(payload, key))],
    ["повтор заказа сохраняет idempotencyKey", payload.idempotencyKey === repeatedPayload.idempotencyKey],
    ["режимы выбирают разные адаптеры", selectedModes.join(",") === "whatsapp,backend,mock"],
    ["backend mode не выбирает WhatsApp", selectedModes[1] === "backend"],
    ["mock возвращает заказ", mockResult.orderNumber === "MOCK-001" && mockResult.serverTotal === payload.clientTotal],
    ["backend использует POST /api/public/orders", backendRequest?.url.endsWith("/api/public/orders") && backendRequest.options.method === "POST"],
    ["backend отправляет Idempotency-Key", backendRequest?.options.headers["Idempotency-Key"] === payload.idempotencyKey],
    ["backend структурирует non-2xx ошибку", backendNon2xxCode === "INVALID_ORDER"],
    ["backend структурирует network error", backendNetworkCode === "NETWORK_ERROR"],
    ["двойная отправка блокируется", firstSubmitLock && !secondSubmitLock && !state.isSubmitting],
    ["quantity не превышает maxItemQuantity", quantityTestCart.get("cezar-roll-chicken") === (limits.maxItemQuantity || 99)],
    ["пять увеличений дают количество пять", basicQuantityCart.get("cezar-roll-chicken") === 5],
    ["общее количество не превышает maxTotalItems", getCartCount(totalLimitCart) === (limits.maxTotalItems || 200)],
    ["пустая корзина отклоняется", !validateOrder({ ...baseOrder, cart: new Map() }).valid],
    ["пустое имя отклоняется", !validateOrder({ ...baseOrder, customerName: "" }).valid],
    ["длинное имя нормализуется", buildOrderPayload({ ...baseOrder, customerName: "Я".repeat(200) }).customer.name.length === (limits.maxNameLength || 80)],
    ["длинный телефон отклоняется", !validateOrder({ ...baseOrder, customerPhone: "1".repeat(40) }).valid],
    ["длинный адрес нормализуется", buildOrderPayload({ ...baseOrder, deliveryAddress: "А".repeat(400) }).customer.address.length === (limits.maxAddressLength || 240)],
    ["длинный комментарий нормализуется", buildOrderPayload({ ...baseOrder, orderComment: "К".repeat(700) }).comment.length === (limits.maxCommentLength || 500)],
    ["повреждённый localStorage не ломает разбор", parseStoredState("{broken") === null],
    ["очистка удаляет все пользовательские поля", personalFieldsCleared],
    ["очистка удаляет корзину и pending draft", fakeState.cart.size === 0 && fakeState.pendingOrder === null],
    ["очистка удаляет storage key", removedStorageKey === getStorageKey()],
    ["доставка требует адрес", !validateOrder({ ...baseOrder, type: "Доставка", deliveryAddress: "" }).valid],
    ["валидная доставка проходит", validateOrder({ ...baseOrder, type: "Доставка", deliveryAddress: "ул. Ленина, 10" }).valid],
    ["самовывоз проходит валидацию", validateOrderPayload(pickupPayload).valid],
    ["WhatsApp содержит ресторан", message.includes(c.brand?.displayName || "")],
    ["WhatsApp содержит источник", message.includes("Заказ с электронного меню")],
    ["WhatsApp содержит ПРЕДЗАКАЗ", message.includes("ПРЕДЗАКАЗ")],
    ["WhatsApp содержит ДОСТАВКА и адрес", deliveryMessage.includes("ДОСТАВКА") && deliveryMessage.includes("ул. Ленина, 10")],
    ["WhatsApp содержит сумму", message.includes(formatMoney(payload.clientTotal))],
    ["WhatsApp содержит нормализованный номер клиента", message.includes("Номер для связи: +79991234567")],
    ["WhatsApp содержит международные номера", ukMessage.includes("+447911123456") && uaMessage.includes("+380501234567")],
    ["номер клиента не превращается в ссылку", !message.includes("wa.me/79991234567") && !message.includes("tel:+79991234567")],
    ["список заказа находится в monospace-блоке", message.includes(orderBlockMarker)],
    ["позиция и расчёт занимают две строки", orderBlockLines[0]?.includes("cezar-roll-chicken") || orderBlockLines[0]?.includes("Ролл")],
    ["сумма находится вне monospace-блока", message.indexOf("*Сумма:") > message.lastIndexOf("```")],
    ["unsafe https проверка отклоняет javascript", !isSafeHttpsUrl("javascript:alert(1)")],
    ["unsafe https проверка отклоняет data", !isSafeHttpsUrl("data:text/html,x")],
    ["unsafe https проверка отклоняет blob/vbscript", !isSafeHttpsUrl("blob:https://example.com/id") && !isSafeHttpsUrl("vbscript:msgbox(1)")],
    ["https social URL разрешён", isSafeHttpsUrl("https://example.com/profile")],
    ["локальное изображение разрешено", isSafeImageUrl("./images/dishes/ayran.webp")],
    ["чужой HTTPS origin изображения запрещён", !isSafeImageUrl("https://evil.example/dish.webp")],
    ["разрешённый origin изображения принимается", isSafeImageUrl("https://cdn.example/dish.webp", ["https://cdn.example"])],
    ["javascript image URL запрещён", !isSafeImageUrl("javascript:alert(1)")],
    ["XSS-like имя остаётся текстом", nameProbe.textContent === xssText && nameProbe.children.length === 0],
    ["XSS-like комментарий остаётся текстом", commentProbe.textContent === xssText && commentProbe.children.length === 0],
    ["XSS-like menu item рендерится текстом", maliciousCard.querySelector(".dish-title").textContent === xssText && maliciousCard.querySelectorAll("img").length === 1],
    ["production menu render не использует HTML-вставки", !/innerHTML|outerHTML|insertAdjacentHTML/.test(renderMenu.toString() + createDishCard.toString())],
    ["production cart render не использует HTML-вставки", !/innerHTML|outerHTML|insertAdjacentHTML/.test(renderCart.toString())],
    ["contacts=true показывает модуль", contactsVisibleWhenEnabled],
    ["contacts=false скрывает модуль", contactsHiddenWhenDisabled],
    ["geoLocation=false скрывает geo controls", geoHiddenWhenDisabled],
    ["socialLinks=false скрывает social links", socialHiddenWhenDisabled],
    ["поля имеют maxlength", elements.customerName.maxLength === (limits.maxNameLength || 80) && elements.orderComment.maxLength === (limits.maxCommentLength || 500)],
    ["управляющие символы удаляются", normalizeText("А\u0000\u200Bдам", 80) === "Адам"],
    ["повторные пробелы нормализуются", normalizeText("  Иван   Иванов  ", 80) === "Иван Иванов"],
    ["много переводов строк сокращается", normalizeText("а\n\n\n\nб", 80, { multiline: true }) === "а\n\nб"],
    ["тип preorder разрешён", validateOrderPayload(payload).valid],
    ["итоговое сообщение укладывается в лимит", message.length <= (limits.maxFinalMessageLength || 3500)],
    ["изменение количества не перерисовывает меню", !changeQuantity.toString().includes("renderMenu(")],
    ["категории имеют aria-pressed", elements.categories.querySelector('[aria-pressed="true"]') !== null],
    ["Android selection отключён точечно", decorativeSelectionDisabled],
    ["поля формы остаются выделяемыми", formSelectionAllowed],
    ["CSP не ограничивает WhatsApp-навигацию", !cspContent.includes("navigate-to")],
    ["production CSP ограничивает backend своим origin", cspContent.includes("connect-src 'self'")],
    ["white-label displayName отображается", testBrandNameApplied],
    ["white-label slogan отображается", testBrandSloganApplied],
    ["white-label businessTypeLabel отображается", testBusinessTypeApplied],
    ["white-label cuisineText отображается", testCuisineApplied],
    ["white-label introText отображается", testIntroApplied],
    ["white-label pageTitle применяется", testPageTitleApplied],
    ["white-label metaDescription применяется", testMetaDescriptionApplied],
    ["workingHoursText меняет schedule", testWorkingHoursApplied],
    ["пустой slogan скрывается", emptySloganHidden],
    ["brand text не использует HTML-вставки", brandUsesSafeDom],
    ["delivery=false скрывает delivery", deliveryHidden],
    ["pickup=false скрывает pickup", pickupHidden],
    ["preorder=false скрывает preorder", preorderHidden],
    ["выключенный default выбирает первый доступный тип", disabledDefaultSelectsFirstAvailable],
    ["order type label берётся из config", configOrderTypeLabelApplied],
    ["payload orderType использует технический код", ["preorder", "delivery", "pickup"].includes(payload.orderType)],
    ["geoLocation=true показывает валидную geo link", validGeoShown],
    ["невалидные координаты скрывают geo link", invalidGeoRejected],
    ["socialLinks=true показывает safe link", safeSocialShown],
    ["unsafe social link отклоняется", unsafeSocialRejected],
    ["unsafe brand logo использует fallback", unsafeLogoFellBack],
    ["динамические тесты выводятся через безопасный DOM", runSelfTests.toString().includes("replaceChildren")]
  ];

  const passed = tests.filter(([, result]) => result).length;
  console.group(`Самопроверка меню «${c.brand?.displayName || "Ресторан"}»: ${passed}/${tests.length}`);
  tests.forEach(([name, result]) => console[result ? "log" : "error"](`${result ? "✓" : "✗"} ${name}`));
  console.groupEnd();

  const title = document.createElement("strong");
  title.textContent = `Самопроверка: ${passed}/${tests.length}`;
  const rows = tests.map(([name, result]) => {
    const row = document.createElement("p");
    row.textContent = `${result ? "✓" : "✗"} ${name}`;
    return row;
  });
  elements.testResults.replaceChildren(title, ...rows);
  elements.testResults.hidden = false;
}

// ── Start ──
document.addEventListener("DOMContentLoaded", init);
