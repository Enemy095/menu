"use strict";

(function exposeValidation(global) {
  const INVISIBLE_UNICODE = /[\u200B-\u200F\u202A-\u202E\u2060-\u206F\uFEFF]/g;
  const CONTROL_CHARS = /[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]/g;

  function normalizeText(value, maxLength, options = {}) {
    let text = String(value || "")
      .replace(INVISIBLE_UNICODE, "")
      .replace(CONTROL_CHARS, "");
    if (options.multiline) {
      text = text.replace(/\r\n?/g, "\n").replace(/[^\S\n]+/g, " ").replace(/\n{3,}/g, "\n\n");
    } else {
      text = text.replace(/\s+/g, " ");
    }
    text = text.trim();
    return Number.isInteger(maxLength) ? text.slice(0, maxLength) : text;
  }

  function normalizePhone(value) {
    return String(value || "").replace(/[^\d]/g, "");
  }

  function normalizeCustomerPhone(value) {
    let digits = normalizePhone(value);
    if (digits.startsWith("00")) digits = digits.slice(2);
    if (digits.length === 11 && digits.startsWith("8")) {
      digits = `7${digits.slice(1)}`;
    } else if (digits.length === 10 && digits.startsWith("9")) {
      digits = `7${digits}`;
    }
    const normalized = `+${digits}`;
    return /^\+[1-9]\d{7,14}$/.test(normalized) ? normalized : "";
  }

  function isSafeHttpsUrl(value) {
    try {
      const url = new URL(String(value || ""));
      return url.protocol === "https:";
    } catch {
      return false;
    }
  }

  function isSafeImageUrl(value, allowedOrigins = global.APP_CONFIG?.allowedImageOrigins || []) {
    const source = String(value || "").trim();
    if (/^(?:\.\/|\/)?images\/[a-z0-9_./-]+\.(?:png|jpe?g|webp|gif)$/i.test(source)) return true;
    if (!isSafeHttpsUrl(source)) return false;
    try {
      return allowedOrigins.includes(new URL(source).origin);
    } catch {
      return false;
    }
  }

  function allowedOrderTypes(features = global.FEATURE_CONFIG) {
    return [
      features.preorder ? "preorder" : "",
      features.delivery ? "delivery" : "",
      features.pickup ? "pickup" : ""
    ].filter(Boolean);
  }

  function validateOrderPayload(payload, config = global.APP_CONFIG, features = global.FEATURE_CONFIG) {
    if (!payload || typeof payload !== "object") return { valid: false, field: "order", message: "Некорректный заказ" };
    if (!allowedOrderTypes(features).includes(payload.orderType)) return { valid: false, field: "orderType", message: "Недоступный тип заказа" };
    if (!payload.customer?.name) return { valid: false, field: "customerName", message: "Укажите имя" };
    if (payload.customer.name.length > config.maxNameLength) return { valid: false, field: "customerName", message: "Имя слишком длинное" };
    if (!payload.customer?.phone || payload.customer.phone.length > config.maxPhoneLength || !normalizeCustomerPhone(payload.customer.phone)) {
      return { valid: false, field: "customerPhone", message: "Укажите корректный номер для связи" };
    }
    if (payload.orderType === "delivery" && features.delivery && !payload.customer.address) {
      return { valid: false, field: "deliveryAddress", message: "Укажите адрес доставки" };
    }
    if ((payload.customer?.address || "").length > config.maxAddressLength) return { valid: false, field: "deliveryAddress", message: "Адрес слишком длинный" };
    if ((payload.comment || "").length > config.maxCommentLength) return { valid: false, field: "orderComment", message: "Комментарий слишком длинный" };
    if (!Array.isArray(payload.items) || payload.items.length === 0) return { valid: false, field: "cart", message: "Добавьте хотя бы одно блюдо" };
    let totalItems = 0;
    for (const item of payload.items) {
      if (!item || typeof item.menuItemId !== "string" || !Number.isInteger(item.quantity) || item.quantity < 1 || item.quantity > config.maxItemQuantity) {
        return { valid: false, field: "cart", message: "Некорректное количество блюда" };
      }
      totalItems += item.quantity;
    }
    if (totalItems > config.maxTotalItems) return { valid: false, field: "cart", message: "Превышен лимит позиций в заказе" };
    return { valid: true };
  }

  global.StambulValidation = Object.freeze({
    normalizeText,
    normalizePhone,
    normalizeCustomerPhone,
    isSafeHttpsUrl,
    isSafeImageUrl,
    allowedOrderTypes,
    validateOrderPayload
  });
})(window);
