import type { MenuItem } from "@e-menu/api-contract/generated";
import { beforeEach, describe, expect, it } from "vitest";

import {
  CART_SCHEMA_VERSION,
  CART_STORAGE_KEY,
  cartLineId,
  estimatedCartTotalMinor,
  normalizedModifiers,
  reconcileCart,
  type CartCatalog,
  type PersistedCart,
} from "./cart";
import { resetCartStoreForTests, useCartStore } from "./cart-store";

const item: MenuItem = {
  menuItemId: "pizza",
  categoryId: "main",
  name: "Пицца",
  priceMinor: 1000,
  available: true,
  sortOrder: 1,
  modifierGroups: [
    {
      modifierGroupId: "cheese",
      name: "Сыр",
      minSelections: 0,
      maxSelections: 2,
      sortOrder: 1,
      options: [
        { optionId: "extra", name: "Дополнительный", priceDeltaMinor: 250, available: true, sortOrder: 1 },
      ],
    },
  ],
};

const catalog: CartCatalog = { restaurantId: "restaurant", menuVersion: 2, items: [item] };
const empty: PersistedCart = {
  schemaVersion: CART_SCHEMA_VERSION,
  restaurantId: "restaurant",
  menuVersion: 1,
  orderType: "pickup",
  items: [],
};

describe("cart domain", () => {
  beforeEach(() => {
    window.localStorage.clear();
    resetCartStoreForTests();
  });

  it("reconciles menu versions and drops unavailable selections", () => {
    const modifiers = [{ modifierGroupId: "cheese", optionId: "extra", quantity: 1 }];
    const current: PersistedCart = {
      ...empty,
      items: [{ lineId: cartLineId("pizza", modifiers), menuItemId: "pizza", quantity: 2, modifiers }],
    };
    const valid = reconcileCart(current, catalog, { maxItemQuantity: 99, maxTotalItems: 200 });
    expect(valid.notice).toBe("menu-updated");
    expect(estimatedCartTotalMinor(valid.cart, catalog)).toBe(2500);

    const unavailableCatalog = {
      ...catalog,
      items: [{ ...item, available: false }],
    };
    const changed = reconcileCart(current, unavailableCatalog, { maxItemQuantity: 99, maxTotalItems: 200 });
    expect(changed.cart.items).toEqual([]);
    expect(changed.notice).toBe("items-updated");
  });

  it("enforces configuration and absolute quantity ceilings", () => {
    const store = useCartStore.getState();
    for (let index = 0; index < 8; index += 1) {
      store.add(catalog, { maxItemQuantity: 500, maxTotalItems: 3 }, "pizza", []);
    }
    expect(useCartStore.getState().items[0]?.quantity).toBe(3);
    expect(useCartStore.getState().add(catalog, { maxItemQuantity: 500, maxTotalItems: 3 }, "pizza", [])).toBe("limit-reached");
  });

  it("aggregates one identical item variant and refuses a second variant of the same menu item", () => {
    const store = useCartStore.getState();
    const oneCheese = [{ modifierGroupId: "cheese", optionId: "extra", quantity: 1 }];
    const twoCheese = [{ modifierGroupId: "cheese", optionId: "extra", quantity: 2 }];
    expect(store.add(catalog, { maxItemQuantity: 99, maxTotalItems: 200 }, "pizza", oneCheese)).toBe("added");
    expect(store.add(catalog, { maxItemQuantity: 99, maxTotalItems: 200 }, "pizza", oneCheese)).toBe("added");
    expect(store.add(catalog, { maxItemQuantity: 99, maxTotalItems: 200 }, "pizza", twoCheese)).toBe("variant-conflict");
    expect(useCartStore.getState().items).toHaveLength(1);
    expect(useCartStore.getState().items[0]?.quantity).toBe(2);
  });

  it("deduplicates identical modifier pairs and drops corrupt duplicate item variants on reconciliation", () => {
    expect(normalizedModifiers([
      { modifierGroupId: "cheese", optionId: "extra", quantity: 1 },
      { modifierGroupId: "cheese", optionId: "extra", quantity: 1 },
    ])).toEqual([{ modifierGroupId: "cheese", optionId: "extra", quantity: 2 }]);

    const first = [{ modifierGroupId: "cheese", optionId: "extra", quantity: 1 }];
    const second = [{ modifierGroupId: "cheese", optionId: "extra", quantity: 2 }];
    const result = reconcileCart(
      {
        ...empty,
        items: [
          { lineId: cartLineId("pizza", first), menuItemId: "pizza", quantity: 1, modifiers: first },
          { lineId: cartLineId("pizza", second), menuItemId: "pizza", quantity: 1, modifiers: second },
        ],
      },
      catalog,
      { maxItemQuantity: 99, maxTotalItems: 200 },
    );
    expect(result.notice).toBe("items-updated");
    expect(result.cart.items).toHaveLength(1);
  });

  it("locks the submitted revision, clears it immediately on success and preserves out-of-band later state", () => {
    expect(useCartStore.getState().add(catalog, { maxItemQuantity: 99, maxTotalItems: 200 }, "pizza", [])).toBe("added");
    const snapshot = useCartStore.getState().beginSubmission();
    expect(snapshot).not.toBeNull();
    expect(useCartStore.getState().add(catalog, { maxItemQuantity: 99, maxTotalItems: 200 }, "pizza", [])).toBe("locked");
    expect(useCartStore.getState().completeSubmission(snapshot!.revision)).toBe(true);
    expect(useCartStore.getState().items).toEqual([]);
    expect(JSON.parse(window.localStorage.getItem(CART_STORAGE_KEY) ?? "{}").state.items).toEqual([]);

    expect(useCartStore.getState().add(catalog, { maxItemQuantity: 99, maxTotalItems: 200 }, "pizza", [])).toBe("added");
    const secondSnapshot = useCartStore.getState().beginSubmission();
    useCartStore.setState((state) => ({
      revision: state.revision + 1,
      items: [...state.items, { lineId: "later", menuItemId: "later", quantity: 1, modifiers: [] }],
    }));
    expect(useCartStore.getState().completeSubmission(secondSnapshot!.revision)).toBe(false);
    expect(useCartStore.getState().items.some((line) => line.menuItemId === "later")).toBe(true);
  });

  it("removes corrupt and unknown persisted schemas without interpreting them", async () => {
    window.localStorage.setItem(CART_STORAGE_KEY, "{broken");
    await useCartStore.persist.rehydrate();
    expect(window.localStorage.getItem(CART_STORAGE_KEY)).not.toContain("broken");

    window.localStorage.setItem(
      CART_STORAGE_KEY,
      JSON.stringify({ state: { ...empty, schemaVersion: 2 }, version: 2 }),
    );
    await useCartStore.persist.rehydrate();
    expect(window.localStorage.getItem(CART_STORAGE_KEY)).not.toContain('"schemaVersion":2');
    expect(useCartStore.getState().items).toEqual([]);
  });

  it("clears state when a persisted restaurant scope does not match", () => {
    const result = reconcileCart(
      { ...empty, restaurantId: "other", items: [{ lineId: "pizza", menuItemId: "pizza", quantity: 1, modifiers: [] }] },
      catalog,
      { maxItemQuantity: 99, maxTotalItems: 200 },
    );
    expect(result.notice).toBe("restaurant-changed");
    expect(result.cart.items).toEqual([]);
  });
});
