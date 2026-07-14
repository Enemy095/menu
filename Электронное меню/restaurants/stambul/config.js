"use strict";

/**
 * Stambul restaurant configuration.
 *
 * This file contains the complete brand identity, contact info,
 * feature flags, and operational settings for the Stambul restaurant.
 *
 * NOTE: This is the initial migration from the original config.js.
 * The original globals (APP_CONFIG, BRAND_CONFIG, etc.) remain untouched
 * for backward compatibility. This file is the NEW source of truth for
 * the SaaS architecture. The bootstrap layer will read from here.
 */

window.RESTAURANT_STAMBUL = Object.freeze({
  slug: "stambul",
  theme: "stambul",
  submitMode: "whatsapp",
  managerPhone: "79886953807",
  apiBaseUrl: "",
  allowedImageOrigins: Object.freeze([]),
  clientMenuVersion: 1,

  brand: Object.freeze({
    displayName: "Ресторан-кафе у Ризвана",
    shortName: "у Ризвана",
    businessTypeLabel: "Ресторан-кафе",
    cuisineText: "Турецкая кухня",
    slogan: "Вкус Стамбула рядом с вами",
    introText: "Выбирайте любимые блюда — мы соберём заказ и передадим его менеджеру в WhatsApp.",
    pageTitle: "У ризвана — электронное меню",
    metaDescription: "Электронное меню ресторан-кафе «У Ризвана»",
    logoUrl: "./restaurants/stambul/branding/logo.png",
    logoAlt: "Логотип ресторан-кафе «У Ризвана»",
    menuSection: Object.freeze({
      eyebrow: "Наше меню",
      title: "Выберите блюда"
    }),
    cartTexts: Object.freeze({
      cartBarLabel: "Посмотреть заказ",
      drawerEyebrow: "Проверьте детали",
      title: "Ваш заказ",
      clearButton: "Очистить",
      closeLabel: "Закрыть корзину"
    }),
    orderTexts: Object.freeze({
      typeLegend: "Как хотите получить заказ?",
      submitWhatsapp: "Отправить заказ в WhatsApp",
      submitBackend: "Оформить заказ",
      submitMock: "Создать тестовый заказ",
      noteWhatsapp: "Откроется WhatsApp с готовым текстом заказа",
      noteBackend: "Заказ будет отправлен в кафе",
      noteMock: "Тестовый режим без отправки заказа"
    }),
    formTexts: Object.freeze({
      phoneLabel: "Номер для связи",
      phonePlaceholder: "Введите номер для связи",
      phoneHint: "Можно ввести российский или международный номер.",
      phoneError: "Укажите корректный номер для связи",
      nameLabel: "Ваше имя",
      namePlaceholder: "Например, Адам",
      guestCountLabel: "Количество гостей",
      optionalLabel: "необязательно",
      guestCountPlaceholder: "Например, 2",
      addressLabel: "Адрес доставки",
      addressPlaceholder: "Улица, дом, квартира",
      addressError: "Укажите адрес доставки",
      commentLabel: "Комментарий",
      commentPlaceholder: "Без лука, позвонить заранее…"
    })
  }),

  profile: Object.freeze({
    phone: "+7 (989) 442-81-81",
    publicPhoneLabel: "Позвонить ресторану",
    address: "",
    workingHoursText: "Ежедневно с 10:00 до 23:00",
    location: Object.freeze({
      latitude: 0,
      longitude: 0,
      label: ""
    }),
    socialLinks: Object.freeze([])
  }),

  // Features are defined in features.js as the single source of truth.
  // The bootstrap layer merges them into config at load time.
  // This avoids duplication and ensures one place to edit feature flags.

  orderTypes: Object.freeze([
    Object.freeze({ code: "preorder", label: "Предзаказ", legacyValue: "Предзаказ", feature: "preorder", default: true }),
    Object.freeze({ code: "delivery", label: "Доставка", legacyValue: "Доставка", feature: "delivery" }),
    Object.freeze({ code: "pickup", label: "Самовывоз", legacyValue: "Самовывоз", feature: "pickup" })
  ]),

  limits: Object.freeze({
    maxItemQuantity: 99,
    maxTotalItems: 200,
    maxNameLength: 80,
    maxPhoneLength: 32,
    maxAddressLength: 240,
    maxCommentLength: 500,
    maxFinalMessageLength: 3500,
    delayedOrderConfirmationMinutes: 15,
    clientMenuVersion: 1
  })
});
