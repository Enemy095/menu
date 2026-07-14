"use strict";

/**
 * Adamas restaurant configuration.
 *
 * Independent from Стамбул — no shared state, no code coupling.
 * Theme: delivery (dark delivery UI)
 */

window.RESTAURANT_ADAMAS = Object.freeze({
  slug: "adamas",
  theme: "delivery",
  submitMode: "whatsapp",
  managerPhone: "79991234567",
  apiBaseUrl: "",
  allowedImageOrigins: Object.freeze([]),
  clientMenuVersion: 1,

  hero: Object.freeze({
    image: "./restaurants/adamas/branding/hero-burger.png",
    imageAlt: "Аппетитный бургер с сыром и овощами",
    titleLine1: "ВКУСНАЯ ЕДА",
    titleLine2: "БЫСТРАЯ ДОСТАВКА",
    subtitle: "Пицца • Роллы • Бургеры • Гарниры",
    ctaText: "Смотреть меню",
    benefits: Object.freeze([
      Object.freeze({ icon: "🔥", text: "Горячая еда" }),
      Object.freeze({ icon: "⏱", text: "От 40 минут" }),
      Object.freeze({ icon: "🚗", text: "Доставка от 1000 ₽ бесплатно" })
    ])
  }),

  brand: Object.freeze({
    displayName: "Адамас",
    shortName: "Адамас",
    businessTypeLabel: "Служба доставки еды",
    cuisineText: "Пицца • Роллы • Бургеры • Гарниры",
    slogan: "ВКУСНАЯ ЕДА БЫСТРАЯ ДОСТАВКА",
    introText: "Закажите любимые блюда — мы доставим быстро и горячо.",
    pageTitle: "Адамас — доставка еды",
    metaDescription: "Доставка еды Адамас — пицца, роллы, бургеры, гарниры. Быстрая доставка.",
    logoUrl: "./restaurants/adamas/branding/hero-burger.png",
    logoAlt: "Логотип Адамас",
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
      submitWhatsapp: "Оформить заказ",
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
    phone: "+7 (999) 123-45-67",
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

  // Features defined in features.js as single source of truth.
  // Bootstrap merges them at load time.

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
