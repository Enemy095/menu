import type { OrderLimits } from "@e-menu/api-contract/generated";
import { describe, expect, it } from "vitest";

import { buildOrderRequest, CheckoutPayloadError, createCheckoutSchema } from "./checkout";

const limits: OrderLimits = {
  maxItemQuantity: 99,
  maxTotalItems: 200,
  maxNameLength: 80,
  maxPhoneLength: 32,
  maxAddressLength: 240,
  maxCommentLength: 500,
  delayedOrderConfirmationMinutes: 10,
};

const lines = [{ lineId: "pizza", menuItemId: "pizza", quantity: 2, modifiers: [] }];

describe("checkout boundary", () => {
  it("normalizes text and requires delivery address", () => {
    const schema = createCheckoutSchema(limits, ["delivery"]);
    expect(
      schema.safeParse({
        orderType: "delivery",
        name: "  Анна\u200B  Иванова ",
        phone: "+7 (999) 000-00-00",
        address: "",
        guestCount: "",
        comment: "  без\nлука ",
      }).success,
    ).toBe(false);

    const parsed = schema.parse({
      orderType: "delivery",
      name: "  Анна\u200B  Иванова ",
      phone: "+7 (999) 000-00-00",
      address: "  Улица  1 ",
      guestCount: "2",
      comment: "  без\nлука ",
    });
    expect(parsed).toMatchObject({
      name: "Анна Иванова",
      phone: "+79990000000",
      address: "Улица 1",
      guestCount: 2,
      comment: "безлука",
    });
  });

  it("builds the generated request without a client price or total", () => {
    const request = buildOrderRequest(
      {
        orderType: "pickup",
        name: "Анна",
        phone: "+79990000000",
        address: "",
        comment: "",
      },
      lines,
      7,
    );
    expect(request).toMatchObject({
      orderType: "pickup",
      clientMenuVersion: 7,
      items: [{ menuItemId: "pizza", quantity: 2, modifiers: [] }],
    });
    expect(JSON.stringify(request)).not.toMatch(/total|price/iu);
  });

  it("rejects order types disabled by public configuration", () => {
    const result = createCheckoutSchema(limits, ["pickup"]).safeParse({
      orderType: "delivery",
      name: "Анна",
      phone: "+79990000000",
      address: "Улица 1",
      comment: "",
    });
    expect(result.success).toBe(false);
  });

  it("canonicalizes duplicate pairs and never emits duplicate menu item IDs", () => {
    const duplicateLines = [
      {
        lineId: "pizza-a",
        menuItemId: "pizza",
        quantity: 1,
        modifiers: [
          { modifierGroupId: "sauce", optionId: "hot", quantity: 1 },
          { modifierGroupId: "sauce", optionId: "hot", quantity: 1 },
        ],
      },
      {
        lineId: "pizza-b",
        menuItemId: "pizza",
        quantity: 2,
        modifiers: [{ modifierGroupId: "sauce", optionId: "hot", quantity: 2 }],
      },
    ];
    const request = buildOrderRequest(
      { orderType: "pickup", name: "Анна", phone: "+79990000000", address: "", comment: "" },
      duplicateLines,
      1,
    );
    const serialized = JSON.parse(JSON.stringify(request)) as { items: unknown[] };
    expect(serialized.items).toEqual([
      {
        menuItemId: "pizza",
        quantity: 3,
        modifiers: [{ modifierGroupId: "sauce", optionId: "hot", quantity: 2 }],
      },
    ]);
  });

  it.each([0, -1, 1.5, Number.NaN])(
    "rejects malformed duplicate line quantity %s before aggregation",
    (quantity) => {
      const base = {
        orderType: "pickup" as const,
        name: "Анна",
        phone: "+79990000000",
        address: "",
        comment: "",
      };
      expect(() =>
        buildOrderRequest(
          base,
          [
            { lineId: "pizza-a", menuItemId: "pizza", quantity: 1, modifiers: [] },
            { lineId: "pizza-b", menuItemId: "pizza", quantity, modifiers: [] },
          ],
          1,
        ),
      ).toThrow(CheckoutPayloadError);
    },
  );

  it("rejects duplicate quantities whose aggregate exceeds the per-item contract bound", () => {
    const base = {
      orderType: "pickup" as const,
      name: "Анна",
      phone: "+79990000000",
      address: "",
      comment: "",
    };
    expect(() =>
      buildOrderRequest(
        base,
        [
          { lineId: "pizza-a", menuItemId: "pizza", quantity: 60, modifiers: [] },
          { lineId: "pizza-b", menuItemId: "pizza", quantity: 40, modifiers: [] },
        ],
        1,
      ),
    ).toThrow(CheckoutPayloadError);
  });

  it("rejects multiple variants and more than fifty modifier selections defensively", () => {
    const base = { orderType: "pickup" as const, name: "Анна", phone: "+79990000000", address: "", comment: "" };
    expect(() => buildOrderRequest(base, [
      { lineId: "a", menuItemId: "pizza", quantity: 1, modifiers: [] },
      {
        lineId: "b",
        menuItemId: "pizza",
        quantity: 1,
        modifiers: [{ modifierGroupId: "sauce", optionId: "hot", quantity: 1 }],
      },
    ], 1)).toThrow(CheckoutPayloadError);

    const modifiers = Array.from({ length: 51 }, (_, index) => ({
      modifierGroupId: `group-${index}`,
      optionId: `option-${index}`,
      quantity: 1,
    }));
    expect(() => buildOrderRequest(base, [
      { lineId: "too-many", menuItemId: "pizza", quantity: 1, modifiers },
    ], 1)).toThrow(CheckoutPayloadError);
  });
});
