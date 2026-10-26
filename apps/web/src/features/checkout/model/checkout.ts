import type {
  CreateOrderItem,
  CreateOrderRequest,
  OrderLimits,
} from "@e-menu/api-contract/generated";
import { CreateOrderRequestFromJSON } from "@e-menu/api-contract/generated";
import { z } from "zod";

import {
  normalizedModifiers,
  type CartLine,
  type CartOrderType,
} from "@/entities/cart/model/cart";

const UNSAFE_TEXT = /[\u0000-\u001F\u007F-\u009F\u200B-\u200F\u202A-\u202E\u2060-\u206F\uFEFF]/gu;

export type CheckoutFields = {
  orderType: CartOrderType;
  name: string;
  phone: string;
  address: string;
  guestCount?: number;
  comment: string;
};

export function normalizeCheckoutText(value: string): string {
  return value.normalize("NFKC").replace(UNSAFE_TEXT, "").replace(/\s+/gu, " ").trim();
}

export function normalizePhone(value: string): string {
  const normalized = value.normalize("NFKC").trim();
  const prefix = normalized.startsWith("+") ? "+" : "";
  return `${prefix}${normalized.replace(/\D/gu, "")}`;
}

export function createCheckoutSchema(
  limits: OrderLimits,
  availableOrderTypes: CartOrderType[],
) {
  const maxName = Math.min(80, limits.maxNameLength);
  const maxPhone = Math.min(32, limits.maxPhoneLength);
  const maxAddress = Math.min(240, limits.maxAddressLength);
  const maxComment = Math.min(500, limits.maxCommentLength);

  return z
    .object({
      orderType: z.enum(["delivery", "pickup", "preorder"]),
      name: z
        .string()
        .transform(normalizeCheckoutText)
        .pipe(z.string().min(1, "Укажите имя").max(maxName, `Не больше ${maxName} символов`)),
      phone: z
        .string()
        .transform(normalizePhone)
        .pipe(
          z
            .string()
            .max(maxPhone, `Не больше ${maxPhone} символов`)
            .regex(/^\+?\d{5,15}$/u, "Укажите телефон из 5–15 цифр"),
        ),
      address: z.string().transform(normalizeCheckoutText),
      guestCount: z
        .preprocess((value) => (value === "" || value === undefined ? undefined : Number(value)),
          z.number().int().min(1).max(99).optional(),
        ),
      comment: z
        .string()
        .transform(normalizeCheckoutText)
        .pipe(z.string().max(maxComment, `Не больше ${maxComment} символов`)),
    })
    .superRefine((value, context) => {
      if (!availableOrderTypes.includes(value.orderType)) {
        context.addIssue({
          code: "custom",
          path: ["orderType"],
          message: "Этот способ получения недоступен",
        });
      }
      if (value.orderType === "delivery" && !value.address) {
        context.addIssue({ code: "custom", path: ["address"], message: "Укажите адрес доставки" });
      }
      if (value.address.length > maxAddress) {
        context.addIssue({
          code: "custom",
          path: ["address"],
          message: `Не больше ${maxAddress} символов`,
        });
      }
    });
}

export class CheckoutPayloadError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "CheckoutPayloadError";
  }
}

function requestItems(items: CartLine[]): CreateOrderItem[] {
  const result = new Map<string, CreateOrderItem>();
  for (const item of items) {
    if (!Number.isSafeInteger(item.quantity) || item.quantity < 1 || item.quantity > 99) {
      throw new CheckoutPayloadError("Item quantity is outside the contract limit");
    }
    const modifiers = normalizedModifiers(item.modifiers);
    if (modifiers.length > 50 || modifiers.some((modifier) => modifier.quantity > 99)) {
      throw new CheckoutPayloadError("Invalid modifier selection count");
    }
    const existing = result.get(item.menuItemId);
    if (existing) {
      if (JSON.stringify(existing.modifiers) !== JSON.stringify(modifiers)) {
        throw new CheckoutPayloadError("One menu item cannot have multiple modifier variants");
      }
      const quantity = existing.quantity + item.quantity;
      if (!Number.isSafeInteger(quantity) || quantity < 1 || quantity > 99) {
        throw new CheckoutPayloadError("Item quantity exceeds contract limit");
      }
      existing.quantity = quantity;
      continue;
    }
    result.set(item.menuItemId, {
      menuItemId: item.menuItemId,
      quantity: item.quantity,
      modifiers,
    });
  }
  const canonical = [...result.values()];
  if (!canonical.length || canonical.length > 200) {
    throw new CheckoutPayloadError("Item count is outside the contract limit");
  }
  if (canonical.reduce((total, item) => total + item.quantity, 0) > 200) {
    throw new CheckoutPayloadError("Total quantity exceeds the contract limit");
  }
  return canonical;
}

export function buildOrderRequest(
  fields: CheckoutFields,
  items: CartLine[],
  menuVersion: number,
): CreateOrderRequest {
  const base = {
    customer: { name: fields.name, phone: fields.phone },
    items: requestItems(items),
    comment: fields.comment || undefined,
    clientMenuVersion: menuVersion,
  };
  const guestCount = fields.guestCount ? { guestCount: fields.guestCount } : {};

  if (fields.orderType === "delivery") {
    return CreateOrderRequestFromJSON({
      ...base,
      orderType: "delivery",
      fulfillment: { address: fields.address, ...guestCount },
    });
  }
  if (fields.orderType === "preorder") {
    return CreateOrderRequestFromJSON({ ...base, orderType: "preorder", fulfillment: guestCount });
  }
  return CreateOrderRequestFromJSON({ ...base, orderType: "pickup", fulfillment: guestCount });
}
