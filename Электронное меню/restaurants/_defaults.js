"use strict";

/**
 * Default values for all restaurant configuration fields.
 *
 * Used as fallback when a restaurant config is missing a field.
 * Ensures the engine never crashes on incomplete configs.
 */

window.RESTAURANT_DEFAULTS = Object.freeze({
  slug: "stambul",
  theme: "stambul",
  submitMode: "whatsapp",
  managerPhone: "",
  apiBaseUrl: "",
  allowedImageOrigins: Object.freeze([]),
  clientMenuVersion: 1,

  brand: Object.freeze({
    displayName: "Ресторан",
    shortName: "Ресторан",
    businessTypeLabel: "Ресторан-кафе",
    cuisineText: "",
    slogan: "",
    introText: "",
    pageTitle: "Электронное меню",
    metaDescription: "Электронное меню ресторана",
    logoUrl: "",
    logoAlt: "Логотип ресторана",
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
    phone: "",
    publicPhoneLabel: "Позвонить",
    address: "",
    workingHoursText: "",
    location: Object.freeze({ latitude: 0, longitude: 0, label: "" }),
    socialLinks: Object.freeze([])
  }),

  features: Object.freeze({
    orders: true,
    contacts: true,
    geoLocation: false,
    socialLinks: false,
    delivery: false,
    pickup: false,
    preorder: true,
    customTheme: false,
    whatsappFallback: true,
    promotions: false,
    loyalty: false,
    reservations: false
  }),

  orderTypes: Object.freeze([
    Object.freeze({ code: "preorder", label: "Предзаказ", legacyValue: "Предзаказ", feature: "preorder", default: true })
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
