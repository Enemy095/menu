"use strict";

window.APP_CONFIG = Object.freeze({
  orderSubmitMode: "whatsapp",
  apiBaseUrl: "",
  allowedImageOrigins: Object.freeze([]),
  businessSlug: "stambul",
  managerPhone: "79886953807",
  maxItemQuantity: 99,
  maxTotalItems: 200,
  maxNameLength: 80,
  maxPhoneLength: 32,
  maxAddressLength: 240,
  maxCommentLength: 500,
  maxFinalMessageLength: 3500,
  delayedOrderConfirmationMinutes: 15,
  clientMenuVersion: 1
});

window.BRAND_CONFIG = Object.freeze({
  displayName: "Ресторан-кафе у Ризвана",
  shortName: "у Ризвана",
  businessTypeLabel: "Ресторан-кафе",
  cuisineText: "Турецкая кухня",
  slogan: "Вкус Стамбула рядом с вами",
  introText: "Выбирайте любимые блюда — мы соберём заказ и передадим его менеджеру в WhatsApp.",
  pageTitle: "У ризвана — электронное меню",
  metaDescription: "Электронное меню ресторан-кафе «У Ризвана»",
  logoUrl: "./images/logo-stambul.png",
  avatarUrl: "./images/logo-stambul.png",
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
  }),
  theme: Object.freeze({
    primaryColor: "#721f2a",
    primaryTextColor: "#fff9ed",
    accentColor: "#c8a467",
    backgroundColor: "#f7f0e3",
    surfaceColor: "#fffdf8",
    textColor: "#2e2020",
    mutedTextColor: "#7e6c66",
    borderColor: "rgba(91, 38, 41, .13)",
    cardRadius: "21px",
    buttonRadius: "18px"
  })
});

window.ORDER_TYPE_CONFIG = Object.freeze([
  Object.freeze({ code: "preorder", label: "Предзаказ", legacyValue: "Предзаказ", feature: "preorder", default: true }),
  Object.freeze({ code: "delivery", label: "Доставка", legacyValue: "Доставка", feature: "delivery" }),
  Object.freeze({ code: "pickup", label: "Самовывоз", legacyValue: "Самовывоз", feature: "pickup" })
]);

window.FEATURE_CONFIG = Object.freeze({
  orders: true,
  contacts: true,
  geoLocation: true,
  socialLinks: false,
  delivery: true,
  pickup: true,
  preorder: true,
  customTheme: true,
  whatsappFallback: true
});

window.BUSINESS_PROFILE_CONFIG = Object.freeze({
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
});
