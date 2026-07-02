"use strict";

window.APP_CONFIG = Object.freeze({
  orderSubmitMode: "whatsapp",
  apiBaseUrl: "",
  allowedImageOrigins: Object.freeze([]),
  businessSlug: "stambul",
  managerPhone: "79292979937",
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
  displayName: "Ресторан-кафе Стамбул",
  slogan: "Вкус Стамбула рядом с вами",
  logoUrl: "./images/logo-stambul.png",
  avatarUrl: "./images/logo-stambul.png",
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
  phone: "+7 (929) 297-99-37",
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
