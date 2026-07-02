"use strict";

const STORAGE_KEY = "stambul-menu-cart-v1";
const { normalizeText, normalizePhone, normalizeCustomerPhone, isSafeHttpsUrl, isSafeImageUrl, validateOrderPayload } = window.StambulValidation;
const { createIdempotencyKey, buildWhatsAppMessage, buildWhatsAppUrl, submitOrder, submitOrderToBackend, submitOrderToMock } = window.StambulOrderSubmit;
const CONFIG = Object.freeze({
  restaurantName: BRAND_CONFIG.displayName,
  cuisine: "Турецкая кухня",
  schedule: BUSINESS_PROFILE_CONFIG.workingHoursText.replace(/^Ежедневно\s*/i, ""),
  currencyText: "₽"
});

const MENU = Object.freeze([
  { id: "mercimek", category: "Супы", name: "Мерджимек чорбасы", description: "Традиционный крем-суп из красной чечевицы с лимоном", price: 320, image: "./images/dishes/mercimek-corbasi.webp" },
  { id: "iskender", category: "Горячее", name: "Искендер-кебаб", description: "Тонкая говядина, лепёшка, томатный соус и йогурт", price: 690, image: "./images/dishes/iskender-kebab.webp" },
  { id: "adana", category: "Горячее", name: "Адана-кебаб", description: "Пряный кебаб из рубленой баранины с овощами", price: 650, image: "./images/dishes/adana-kebab.webp" },
  { id: "pide", category: "Выпечка", name: "Пиде с сыром и мясом", description: "Турецкая лодочка из тонкого теста с сочной начинкой", price: 520, image: "./images/dishes/pide-cheese-meat.webp" },
  { id: "lahmacun", category: "Выпечка", name: "Лахмаджун", description: "Тонкая лепёшка с мясом, томатами и свежей зеленью", price: 390, image: "./images/dishes/lahmacun.webp" },
  { id: "dolma", category: "Закуски", name: "Долма", description: "Виноградные листья с пряной мясной начинкой", price: 460, image: "./images/dishes/dolma.webp" },
  { id: "meze", category: "Закуски", name: "Ассорти мезе", description: "Хумус, хайдари, бабагануш и свежая лепёшка", price: 580, image: "./images/dishes/meze-assorti.webp" },
  { id: "salad", category: "Салаты", name: "Чобан-салат", description: "Томаты, огурцы, перец, зелень и гранатовый соус", price: 350, image: "./images/dishes/choban-salad.webp" },
  { id: "baklava", category: "Десерты", name: "Пахлава", description: "Слоёное тесто, фисташки и ароматный медовый сироп", price: 330, image: "./images/dishes/baklava.webp" },
  { id: "sutlac", category: "Десерты", name: "Сютлач", description: "Нежный запечённый рисовый пудинг по-турецки", price: 290, image: "./images/dishes/sutlac.webp" },
  { id: "tea", category: "Напитки", name: "Турецкий чай", description: "Крепкий чёрный чай в традиционном стакане", price: 140, image: "./images/dishes/turkish-tea.webp" },
  { id: "ayran", category: "Напитки", name: "Айран", description: "Освежающий кисломолочный напиток", price: 180, image: "./images/dishes/ayran.webp" }
]);

const state = {
  category: "Все",
  cart: new Map(),
  selectedOrderType: "preorder",
  pendingOrder: null,
  isSubmitting: false
};

const OPTIONAL_MODULES = Object.freeze([
  Object.freeze({ feature: "contacts", mount: renderContacts })
]);

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

init();

function init() {
  restoreState();
  applyWhiteLabelConfig();
  applyConfiguredLimits();
  mountOptionalModules();
  renderCategories();
  renderMenu();
  restoreFormFields();
  updateOrderTypeUI();
  updateCartSummary();
  renderCart();
  bindEvents();
  syncDesktopCart();

  if (new URLSearchParams(window.location.search).get("test") === "1") {
    runSelfTests();
  }
}

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

function applyWhiteLabelConfig(
  brand = BRAND_CONFIG,
  profile = BUSINESS_PROFILE_CONFIG,
  features = FEATURE_CONFIG,
  orderTypes = ORDER_TYPE_CONFIG,
  submitMode = APP_CONFIG.orderSubmitMode
) {
  renderBrand(brand);
  setText("[data-brand-business-type]", brand.businessTypeLabel, "Ресторан-кафе");
  setText("[data-brand-cuisine]", brand.cuisineText, CONFIG.cuisine);
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

  applyTheme(brand.theme, features.customTheme);
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

function renderBrand(brand = BRAND_CONFIG) {
  const displayName = normalizeText(brand?.displayName, 120) || "Ресторан-кафе Стамбул";
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
  document.querySelector('meta[name="theme-color"]').content =
    normalizeText(brand?.theme?.primaryColor, 32) || "#641c25";
  document.title = normalizeText(brand?.pageTitle, 180) || `${displayName} — электронное меню`;
}

function applyTheme(theme = BRAND_CONFIG.theme, enabled = true) {
  if (!enabled || !theme) return;
  const values = {
    "--color-primary": theme.primaryColor,
    "--color-primary-text": theme.primaryTextColor,
    "--color-accent": theme.accentColor,
    "--color-background": theme.backgroundColor,
    "--color-surface": theme.surfaceColor,
    "--color-text": theme.textColor,
    "--color-muted": theme.mutedTextColor,
    "--color-border": theme.borderColor,
    "--radius-card": theme.cardRadius,
    "--radius-button": theme.buttonRadius,
    // Legacy aliases keep the existing visual system stable.
    "--burgundy": theme.primaryColor,
    "--gold": theme.accentColor,
    "--cream": theme.backgroundColor,
    "--milk": theme.surfaceColor,
    "--ink": theme.textColor,
    "--muted": theme.mutedTextColor,
    "--line": theme.borderColor
  };
  const root = document.documentElement.style;
  Object.entries(values).forEach(([property, value]) => {
    if (typeof value === "string" && value.trim()) root.setProperty(property, value.trim());
  });
}

function applyConfiguredLimits() {
  elements.customerName.maxLength = APP_CONFIG.maxNameLength;
  elements.customerPhone.maxLength = APP_CONFIG.maxPhoneLength;
  elements.deliveryAddress.maxLength = APP_CONFIG.maxAddressLength;
  elements.orderComment.maxLength = APP_CONFIG.maxCommentLength;
}

function applyFeatureConfig(
  features = FEATURE_CONFIG,
  submitMode = APP_CONFIG.orderSubmitMode,
  orderTypes = ORDER_TYPE_CONFIG,
  orderTexts = BRAND_CONFIG.orderTexts || {}
) {
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

function renderOrderTypeOptions(orderTypes = ORDER_TYPE_CONFIG, features = FEATURE_CONFIG) {
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

function mountOptionalModules() {
  OPTIONAL_MODULES.forEach(module => module.mount(FEATURE_CONFIG, BUSINESS_PROFILE_CONFIG));
}

function renderContacts(features = FEATURE_CONFIG, profile = BUSINESS_PROFILE_CONFIG) {
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

function renderCategories() {
  const categories = ["Все", ...new Set(MENU.map(item => item.category))];
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
  elements.menuList.replaceChildren(...MENU.map(item => createDishCard(item, state.cart.get(item.id) || 0)));
  applyCategoryFilter();
}

function createDishCard(item, quantity = 0) {
    const card = document.createElement("article");
    card.className = "dish-card";
    card.dataset.dishId = item.id;
    card.dataset.category = item.category;

    const visual = document.createElement("div");
    visual.className = "dish-visual";
    const image = document.createElement("img");
    image.src = isSafeImageUrl(item.image) ? item.image :
      (isSafeImageUrl(BRAND_CONFIG.logoUrl) ? BRAND_CONFIG.logoUrl : "./images/logo-stambul.png");
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
  increase.disabled = quantity >= APP_CONFIG.maxItemQuantity || getCartCount() >= APP_CONFIG.maxTotalItems;
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
  const card = elements.menuList.querySelector(`[data-dish-id="${id}"]`);
  if (!card) return;
  const quantity = state.cart.get(id) || 0;
  const value = card.querySelector("[data-quantity-value]");
  const decreaseButton = card.querySelector('[data-action="decrease"]');
  const increaseButton = card.querySelector('[data-action="increase"]');
  if (value) value.textContent = quantity;
  if (decreaseButton) decreaseButton.disabled = quantity === 0;
  if (increaseButton) increaseButton.disabled = quantity >= APP_CONFIG.maxItemQuantity || getCartCount() >= APP_CONFIG.maxTotalItems;
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
  if (!MENU.some(item => item.id === id)) return;
  updateCartQuantity(state.cart, id, delta);
  saveState();
  updateDishCardQuantity(id);
  updateCartSummary();
  if (elements.backdrop.classList.contains("open") || isDesktopLayout()) renderCart();
}

function updateCartQuantity(cart, id, delta) {
  const current = cart.get(id) || 0;
  const currentTotal = getCartCount(cart);
  const allowedDelta = delta > 0 ? Math.min(delta, APP_CONFIG.maxTotalItems - currentTotal) : delta;
  const next = Math.min(APP_CONFIG.maxItemQuantity, Math.max(0, current + allowedDelta));
  if (next === 0) cart.delete(id);
  else cart.set(id, next);
  return next;
}

function getCartLines(cart = state.cart) {
  return [...cart].map(([id, quantity]) => {
    const item = MENU.find(entry => entry.id === id);
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
  const count = getCartCount();
  const total = formatMoney(getCartTotal());
  elements.cartBadge.textContent = count;
  elements.cartBarCount.textContent = count;
  elements.cartBarTotal.textContent = total;
  elements.cartTotal.textContent = total;
  elements.cartBar.hidden = count === 0;
  elements.menuList.querySelectorAll('[data-action="increase"]').forEach(button => {
    const quantity = state.cart.get(button.dataset.id) || 0;
    button.disabled = quantity >= APP_CONFIG.maxItemQuantity || count >= APP_CONFIG.maxTotalItems;
  });
}

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
    removeStoredOrderData(localStorage);
  } catch (error) {
    console.warn("Не удалось очистить сохранение:", error);
  }
  MENU.forEach(item => updateDishCardQuantity(item.id));
  updateCartSummary();
  if (isDesktopLayout()) renderCart();
  closeCart();
  showToast("Корзина очищена");
}

function clearOrderState(targetState) {
  targetState.cart.clear();
  targetState.pendingOrder = null;
}

function removeStoredOrderData(storage) {
  storage.removeItem(STORAGE_KEY);
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

function readOrderData() {
  return {
    type: state.selectedOrderType,
    customerPhone: normalizeText(elements.customerPhone.value, APP_CONFIG.maxPhoneLength),
    customerName: normalizeText(elements.customerName.value, APP_CONFIG.maxNameLength),
    guestCount: elements.guestCount.value.trim(),
    deliveryAddress: normalizeText(elements.deliveryAddress.value, APP_CONFIG.maxAddressLength),
    orderComment: normalizeText(elements.orderComment.value, APP_CONFIG.maxCommentLength, { multiline: true }),
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
  const normalizedPhone = normalizeCustomerPhone(order.customerPhone);
  const draft = {
    businessSlug: APP_CONFIG.businessSlug,
    orderType: toApiOrderType(order.type),
    salesChannel: "public_menu",
    customer: {
      name: normalizeText(order.customerName, APP_CONFIG.maxNameLength),
      phone: normalizedPhone,
      address: normalizeText(order.deliveryAddress, APP_CONFIG.maxAddressLength)
    },
    items: getCartLines(order.cart).map(item => ({
      menuItemId: item.id,
      quantity: item.quantity
    })),
    comment: normalizeText(order.orderComment, APP_CONFIG.maxCommentLength, { multiline: true }),
    // Предварительный итог для UI. Backend обязан заново получить цены и пересчитать сумму.
    clientTotal: getCartTotal(order.cart),
    clientCreatedAt: new Date().toISOString(),
    idempotencyKey: "",
    clientMenuVersion: APP_CONFIG.clientMenuVersion
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
  if (!FEATURE_CONFIG.orders || !beginSubmission()) return;
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
    if (APP_CONFIG.orderSubmitMode === "mock") {
      showToast(`Тестовый заказ ${result.orderNumber} создан`);
    } else if (APP_CONFIG.orderSubmitMode === "backend") {
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
    restaurantName: CONFIG.restaurantName,
    resolveMenuItem: id => MENU.find(item => item.id === id),
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
  return `${Number(value).toLocaleString("ru-RU")} ${CONFIG.currencyText}`;
}

function saveState() {
  const data = {
    cart: Object.fromEntries(state.cart),
    selectedOrderType: state.selectedOrderType
  };
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(data));
  } catch (error) {
    console.warn("Не удалось сохранить корзину:", error);
  }
}

function restoreState() {
  let raw;
  try {
    raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return;
  } catch (error) {
    console.warn("Хранилище недоступно:", error);
    return;
  }
  const saved = parseStoredState(raw);
  if (!saved) {
    try {
      localStorage.removeItem(STORAGE_KEY);
    } catch {
      // Storage может быть полностью недоступен, приложение всё равно продолжает работу.
    }
    console.warn("Повреждённое сохранение корзины очищено");
    return;
  }

  const validOrderTypes = ["preorder", "delivery", "pickup", "Предзаказ", "Доставка", "Самовывоз"];
  if (validOrderTypes.includes(saved.selectedOrderType)) {
    state.selectedOrderType = toApiOrderType(saved.selectedOrderType);
  }
  if (saved.cart && typeof saved.cart === "object" && !Array.isArray(saved.cart)) {
    Object.entries(saved.cart).forEach(([id, quantity]) => {
      if (MENU.some(item => item.id === id) && Number.isInteger(quantity) && quantity > 0 && quantity <= APP_CONFIG.maxItemQuantity) {
        state.cart.set(id, quantity);
      }
    });
  }
  if (getCartCount() > APP_CONFIG.maxTotalItems) state.cart.clear();
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

async function runSelfTests() {
  const testCart = new Map([["mercimek", 3], ["ayran", 1]]);
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
  const quantityTestCart = new Map([["mercimek", APP_CONFIG.maxItemQuantity]]);
  updateCartQuantity(quantityTestCart, "mercimek", 1);
  const basicQuantityCart = new Map();
  for (let index = 0; index < 5; index += 1) updateCartQuantity(basicQuantityCart, "mercimek", 1);
  const totalLimitCart = new Map([["mercimek", 99], ["ayran", 99]]);
  updateCartQuantity(totalLimitCart, "baklava", 10);

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
  renderContacts({ ...FEATURE_CONFIG, contacts: false });
  const contactsHiddenWhenDisabled = elements.contactSection.hidden;
  renderContacts(FEATURE_CONFIG);
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
  const fakeState = { cart: new Map([["mercimek", 2]]), pendingOrder: { id: "draft" } };
  clearOrderState(fakeState);
  let removedStorageKey = "";
  removeStoredOrderData({ removeItem: key => { removedStorageKey = key; } });
  renderContacts(
    { ...FEATURE_CONFIG, contacts: true, geoLocation: false, socialLinks: false },
    {
      ...BUSINESS_PROFILE_CONFIG,
      location: { latitude: 55.75, longitude: 37.61, label: "Карта" },
      socialLinks: [{ type: "test", label: "Соцсеть", url: "https://example.com/social" }]
    }
  );
  const geoHiddenWhenDisabled = !elements.contactSection.querySelector('a[href*="google.com/maps"]');
  const socialHiddenWhenDisabled = !elements.contactSection.querySelector('a[href="https://example.com/social"]');
  renderContacts(FEATURE_CONFIG);
  const cspContent = document.querySelector('meta[http-equiv="Content-Security-Policy"]')?.content || "";

  const testBrand = {
    ...BRAND_CONFIG,
    displayName: "Тестовое кафе",
    businessTypeLabel: "Кафе",
    cuisineText: "Авторская кухня",
    slogan: "Тестовый слоган",
    introText: "Тестовый intro-текст",
    pageTitle: "Тестовое кафе — меню",
    metaDescription: "Тестовое описание меню",
    logoUrl: "javascript:alert(1)",
    theme: { ...BRAND_CONFIG.theme, primaryColor: "#2E7D32" }
  };
  const testProfile = { ...BUSINESS_PROFILE_CONFIG, workingHoursText: "По будням с 09:00 до 18:00" };
  applyWhiteLabelConfig(testBrand, testProfile, FEATURE_CONFIG, ORDER_TYPE_CONFIG);
  const testBrandNameApplied = [...document.querySelectorAll("[data-brand-display-name]")]
    .every(element => element.textContent === "Тестовое кафе");
  const testBrandSloganApplied = elements.brandSlogan.textContent === "Тестовый слоган" && !elements.brandSlogan.hidden;
  const testBusinessTypeApplied = document.querySelector("[data-brand-business-type]").textContent === "Кафе";
  const testCuisineApplied = document.querySelector("[data-brand-cuisine]").textContent === "Авторская кухня";
  const testIntroApplied = document.querySelector("[data-brand-intro]").textContent === "Тестовый intro-текст";
  const testPageTitleApplied = document.title === "Тестовое кафе — меню";
  const testMetaDescriptionApplied =
    document.querySelector('meta[name="description"]').content === "Тестовое описание меню";
  const testThemeColorApplied =
    document.querySelector('meta[name="theme-color"]').content === "#2E7D32";
  const testWorkingHoursApplied = elements.scheduleText.textContent === "По будням с 09:00 до 18:00";
  const unsafeLogoFellBack = elements.brandLogo.getAttribute("src") === "./images/logo-stambul.png";
  const brandUsesSafeDom = !/innerHTML|outerHTML|insertAdjacentHTML/.test(
    applyWhiteLabelConfig.toString() + renderBrand.toString() + applyFormTexts.toString()
  );
  applyWhiteLabelConfig({ ...testBrand, slogan: "" }, testProfile, FEATURE_CONFIG, ORDER_TYPE_CONFIG);
  const emptySloganHidden = elements.brandSlogan.hidden && elements.brandSlogan.textContent === "";
  applyWhiteLabelConfig();

  const testTheme = {
    ...BRAND_CONFIG.theme,
    primaryColor: "#2E7D32",
    backgroundColor: "#E8F5E9",
    cardRadius: "31px",
    buttonRadius: "13px"
  };
  applyTheme(testTheme);
  const rootStyle = document.documentElement.style;
  const testPrimaryApplied = rootStyle.getPropertyValue("--color-primary").trim() === "#2E7D32";
  const testBackgroundApplied = rootStyle.getPropertyValue("--color-background").trim() === "#E8F5E9";
  const testCardRadiusApplied = rootStyle.getPropertyValue("--radius-card").trim() === "31px";
  const testButtonRadiusApplied = rootStyle.getPropertyValue("--radius-button").trim() === "13px";
  const allThemeFieldsApplied = [
    "--color-primary", "--color-primary-text", "--color-accent", "--color-background",
    "--color-surface", "--color-text", "--color-muted", "--color-border",
    "--radius-card", "--radius-button"
  ].every(property => rootStyle.getPropertyValue(property).trim());
  applyTheme(BRAND_CONFIG.theme);

  const originalOrderType = state.selectedOrderType;
  applyFeatureConfig({ ...FEATURE_CONFIG, delivery: false });
  const deliveryHidden = !elements.orderForm.querySelector('input[value="delivery"]');
  applyFeatureConfig({ ...FEATURE_CONFIG, pickup: false });
  const pickupHidden = !elements.orderForm.querySelector('input[value="pickup"]');
  state.selectedOrderType = "preorder";
  applyFeatureConfig({ ...FEATURE_CONFIG, preorder: false });
  const preorderHidden = !elements.orderForm.querySelector('input[value="preorder"]');
  const disabledDefaultSelectsFirstAvailable = state.selectedOrderType === "delivery";
  state.selectedOrderType = originalOrderType;
  applyFeatureConfig(FEATURE_CONFIG);
  updateOrderTypeUI();
  const customOrderTypes = ORDER_TYPE_CONFIG.map(type =>
    type.code === "preorder" ? { ...type, label: "Быстрый заказ" } : type
  );
  renderOrderTypeOptions(customOrderTypes, FEATURE_CONFIG);
  const configOrderTypeLabelApplied =
    elements.orderForm.querySelector('input[value="preorder"] + span')?.textContent === "Быстрый заказ";
  renderOrderTypeOptions(ORDER_TYPE_CONFIG, FEATURE_CONFIG);
  updateOrderTypeUI();

  const profileWithLinks = {
    ...BUSINESS_PROFILE_CONFIG,
    location: { latitude: 55.75, longitude: 37.61, label: "Открыть карту" },
    socialLinks: [
      { type: "safe", label: "Безопасная сеть", url: "https://example.com/social" },
      { type: "unsafe", label: "Опасная сеть", url: "javascript:alert(1)" }
    ]
  };
  renderContacts({ ...FEATURE_CONFIG, contacts: true, geoLocation: true, socialLinks: true }, profileWithLinks);
  const validGeoShown = Boolean(elements.contactSection.querySelector('a[href*="google.com/maps"]'));
  const safeSocialShown = Boolean(elements.contactSection.querySelector('a[href="https://example.com/social"]'));
  const unsafeSocialRejected = ![...elements.contactSection.querySelectorAll("a")]
    .some(link => link.textContent === "Опасная сеть");
  renderContacts(
    { ...FEATURE_CONFIG, contacts: true, geoLocation: true, socialLinks: false },
    { ...profileWithLinks, location: { latitude: 999, longitude: 999, label: "Неверная карта" } }
  );
  const invalidGeoRejected = !elements.contactSection.querySelector('a[href*="google.com/maps"]');
  renderContacts(FEATURE_CONFIG);

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
    ["URL phone не меняет получателя", APP_CONFIG.managerPhone === "79292979937"],
    ["прочие malicious URL params не меняют config", APP_CONFIG.businessSlug === "stambul" && APP_CONFIG.orderSubmitMode === "whatsapp" && APP_CONFIG.apiBaseUrl === ""],
    ["payload содержит businessSlug", payload.businessSlug === APP_CONFIG.businessSlug],
    ["payload содержит public_menu", payload.salesChannel === "public_menu"],
    ["payload содержит customer", payload.customer.name === "Адам" && payload.customer.phone === "+79991234567"],
    ["payload содержит только ID и количество", payload.items[0].menuItemId === "mercimek" && !("price" in payload.items[0])],
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
    ["quantity не превышает maxItemQuantity", quantityTestCart.get("mercimek") === APP_CONFIG.maxItemQuantity],
    ["пять увеличений дают количество пять", basicQuantityCart.get("mercimek") === 5],
    ["общее количество не превышает maxTotalItems", getCartCount(totalLimitCart) === APP_CONFIG.maxTotalItems],
    ["пустая корзина отклоняется", !validateOrder({ ...baseOrder, cart: new Map() }).valid],
    ["пустое имя отклоняется", !validateOrder({ ...baseOrder, customerName: "" }).valid],
    ["длинное имя нормализуется", buildOrderPayload({ ...baseOrder, customerName: "Я".repeat(200) }).customer.name.length === APP_CONFIG.maxNameLength],
    ["длинный телефон отклоняется", !validateOrder({ ...baseOrder, customerPhone: "1".repeat(40) }).valid],
    ["длинный адрес нормализуется", buildOrderPayload({ ...baseOrder, deliveryAddress: "А".repeat(400) }).customer.address.length === APP_CONFIG.maxAddressLength],
    ["длинный комментарий нормализуется", buildOrderPayload({ ...baseOrder, orderComment: "К".repeat(700) }).comment.length === APP_CONFIG.maxCommentLength],
    ["повреждённый localStorage не ломает разбор", parseStoredState("{broken") === null],
    ["очистка удаляет все пользовательские поля", personalFieldsCleared],
    ["очистка удаляет корзину и pending draft", fakeState.cart.size === 0 && fakeState.pendingOrder === null],
    ["очистка удаляет storage key", removedStorageKey === STORAGE_KEY],
    ["доставка требует адрес", !validateOrder({ ...baseOrder, type: "Доставка", deliveryAddress: "" }).valid],
    ["валидная доставка проходит", validateOrder({ ...baseOrder, type: "Доставка", deliveryAddress: "ул. Ленина, 10" }).valid],
    ["самовывоз проходит валидацию", validateOrderPayload(pickupPayload).valid],
    ["WhatsApp содержит ресторан", message.includes(CONFIG.restaurantName)],
    ["WhatsApp содержит источник", message.includes("Заказ с электронного меню")],
    ["WhatsApp содержит ПРЕДЗАКАЗ", message.includes("ПРЕДЗАКАЗ")],
    ["WhatsApp содержит ДОСТАВКА и адрес", deliveryMessage.includes("ДОСТАВКА") && deliveryMessage.includes("ул. Ленина, 10")],
    ["WhatsApp содержит позиции", message.includes("Мерджимек чорбасы") && message.includes("Айран")],
    ["WhatsApp содержит сумму", message.includes(formatMoney(payload.clientTotal))],
    ["WhatsApp содержит нормализованный номер клиента", message.includes("Номер для связи: +79991234567")],
    ["WhatsApp содержит международные номера", ukMessage.includes("+447911123456") && uaMessage.includes("+380501234567")],
    ["номер клиента не превращается в ссылку", !message.includes("wa.me/79991234567") && !message.includes("tel:+79991234567")],
    ["заказ направляется только managerPhone", buildWhatsAppUrl(APP_CONFIG.managerPhone, "Заказ").startsWith(`https://wa.me/${APP_CONFIG.managerPhone}?text=`)],
    ["WhatsApp URL кодирует текст", buildWhatsAppUrl(APP_CONFIG.managerPhone, "Тест заказа") === `https://wa.me/${APP_CONFIG.managerPhone}?text=${encodeURIComponent("Тест заказа")}`],
    ["список заказа находится в monospace-блоке", message.includes(orderBlockMarker)],
    ["позиция и расчёт занимают две строки", orderBlockLines[0] === "1) [3 шт] Мерджимек чорбасы" && orderBlockLines[1]?.includes("3 × 320 ₽ =")],
    ["между позициями нет пустых строк", !orderBlock.includes("\n\n")],
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
    ["поля имеют maxlength", elements.customerName.maxLength === APP_CONFIG.maxNameLength && elements.orderComment.maxLength === APP_CONFIG.maxCommentLength],
    ["управляющие символы удаляются", normalizeText("А\u0000\u200Bдам", 80) === "Адам"],
    ["повторные пробелы нормализуются", normalizeText("  Иван   Иванов  ", 80) === "Иван Иванов"],
    ["много переводов строк сокращается", normalizeText("а\n\n\n\nб", 80, { multiline: true }) === "а\n\nб"],
    ["тип preorder разрешён", validateOrderPayload(payload).valid],
    ["выключенный тип отклоняется", !validateOrderPayload(payload, APP_CONFIG, { ...FEATURE_CONFIG, preorder: false }).valid],
    ["итоговое сообщение укладывается в лимит", message.length <= APP_CONFIG.maxFinalMessageLength],
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
    ["white-label theme-color применяется", testThemeColorApplied],
    ["workingHoursText меняет schedule", testWorkingHoursApplied],
    ["пустой slogan скрывается", emptySloganHidden],
    ["brand text не использует HTML-вставки", brandUsesSafeDom],
    ["primaryColor меняет --color-primary", testPrimaryApplied],
    ["backgroundColor меняет --color-background", testBackgroundApplied],
    ["cardRadius меняет --radius-card", testCardRadiusApplied],
    ["buttonRadius меняет --radius-button", testButtonRadiusApplied],
    ["все theme-поля подключены к CSS variables", allThemeFieldsApplied],
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
  console.group(`Самопроверка меню «${CONFIG.restaurantName}»: ${passed}/${tests.length}`);
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
