"use strict";

(function exposeOrderSubmit(global) {
  function createIdempotencyKey() {
    if (global.crypto?.randomUUID) return global.crypto.randomUUID();
    const random = Math.random().toString(36).slice(2);
    return `order-${Date.now()}-${random}`;
  }

  function buildWhatsAppOrderItemLine(item, index, formatMoney) {
    const prefix = `${index + 1}) [`;
    const nameLine = `${prefix}${item.quantity} шт] ${item.name}`;
    const indent = " ".repeat(prefix.length);
    const formula = `${indent}${item.quantity} × ${formatMoney(item.price)} =`;
    const lineTotal = formatMoney(item.total);
    const spacesCount = Math.max(1, 34 - formula.length - lineTotal.length);
    return `${nameLine}\n${formula}${" ".repeat(spacesCount)}${lineTotal}`;
  }

  function buildWhatsAppMessage(payload, context) {
    const resolveMenuItem = context.resolveMenuItem;
    const formatMoney = context.formatMoney;
    const orderLines = payload.items.map((entry, index) => {
      const menuItem = resolveMenuItem(entry.menuItemId);
      return buildWhatsAppOrderItemLine({
        name: menuItem?.name || entry.menuItemId,
        price: menuItem?.price || 0,
        quantity: entry.quantity,
        total: (menuItem?.price || 0) * entry.quantity
      }, index, formatMoney);
    }).join("\n");
    const typeLabel = { preorder: "ПРЕДЗАКАЗ", delivery: "ДОСТАВКА", pickup: "САМОВЫВОЗ" }[payload.orderType] || payload.orderType;
    const details = [
      `*Тип заказа: ${typeLabel}*`,
      `Номер для связи: ${payload.customer.phone}`,
      payload.customer.name ? `Имя: ${payload.customer.name}` : "",
      payload.orderType === "delivery" && payload.customer.address ? `Адрес доставки: ${payload.customer.address}` : ""
    ].filter(Boolean).join("\n");
    const comment = payload.comment ? `\n\nКомментарий: ${payload.comment}` : "";
    return `*${context.restaurantName}*\n*Заказ с электронного меню*\n\n${details}\n\n*Заказ:*\n\`\`\`\n${orderLines}\n\`\`\`${comment}\n\n*Сумма: ${formatMoney(payload.clientTotal)}*`;
  }

  function buildWhatsAppUrl(phone, message) {
    return `https://wa.me/${global.StambulValidation.normalizePhone(phone)}?text=${encodeURIComponent(message)}`;
  }

  async function submitOrderToWhatsApp(payload, context) {
    const message = buildWhatsAppMessage(payload, context);
    if (message.length > global.APP_CONFIG.maxFinalMessageLength) {
      throw Object.assign(new Error("Сообщение заказа слишком длинное"), { code: "MESSAGE_TOO_LONG" });
    }
    const phone = global.StambulValidation.normalizePhone(global.APP_CONFIG.managerPhone);
    if (!phone) throw Object.assign(new Error("Не задан номер менеджера"), { code: "INVALID_MANAGER_PHONE" });
    const url = buildWhatsAppUrl(phone, message);
    global.location.assign(url);
    return { status: "redirected", channel: "whatsapp" };
  }

  async function submitOrderToBackend(payload, context = {}) {
    const fetchImpl = context.fetchImpl || global.fetch?.bind(global);
    if (!fetchImpl) throw Object.assign(new Error("Сеть недоступна"), { code: "NETWORK_ERROR" });
    const base = String(global.APP_CONFIG.apiBaseUrl || "").replace(/\/+$/, "");
    try {
      const response = await fetchImpl(`${base}/api/public/orders`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "Idempotency-Key": payload.idempotencyKey
        },
        body: JSON.stringify(payload)
      });
      let body = null;
      try {
        body = await response.json();
      } catch {
        body = null;
      }
      if (!response.ok) {
        const apiError = body?.error;
        throw Object.assign(new Error(apiError?.message || body?.message || "Backend отклонил заказ"), {
          code: apiError?.code || body?.code || "BACKEND_ERROR",
          status: response.status,
          details: apiError?.details || body
        });
      }
      return body;
    } catch (error) {
      if (error?.code === "BACKEND_ERROR" || error?.status) throw error;
      throw Object.assign(new Error("Не удалось связаться с сервером"), { code: "NETWORK_ERROR", cause: error });
    }
  }

  async function submitOrderToMock(payload) {
    return {
      orderNumber: "MOCK-001",
      status: "new",
      serverTotal: payload.clientTotal,
      needsConfirmation: false
    };
  }

  async function submitOrder(payload, context = {}) {
    const mode = context.mode || global.APP_CONFIG.orderSubmitMode;
    const adapters = context.adapters || {
      whatsapp: submitOrderToWhatsApp,
      backend: submitOrderToBackend,
      mock: submitOrderToMock
    };
    switch (mode) {
      case "backend":
        return adapters.backend(payload, context);
      case "mock":
        return adapters.mock(payload, context);
      case "whatsapp":
        return adapters.whatsapp(payload, context);
      default:
        throw Object.assign(new Error("Неизвестный режим отправки"), { code: "INVALID_SUBMIT_MODE" });
    }
  }

  global.StambulOrderSubmit = Object.freeze({
    createIdempotencyKey,
    buildWhatsAppMessage,
    buildWhatsAppUrl,
    submitOrder,
    submitOrderToWhatsApp,
    submitOrderToBackend,
    submitOrderToMock
  });
})(window);
