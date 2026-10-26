import type { MenuItem } from "@e-menu/api-contract/generated";

export const CART_SCHEMA_VERSION = 1 as const;
export const CART_STORAGE_KEY = "e-menu:cart:v1";
export const ABSOLUTE_MAX_ITEM_QUANTITY = 99;
export const ABSOLUTE_MAX_TOTAL_ITEMS = 200;

export type CartOrderType = "delivery" | "pickup" | "preorder";

export type CartModifierSelection = {
  modifierGroupId: string;
  optionId: string;
  quantity: number;
};

export type CartLine = {
  lineId: string;
  menuItemId: string;
  quantity: number;
  modifiers: CartModifierSelection[];
};

export type PersistedCart = {
  schemaVersion: typeof CART_SCHEMA_VERSION;
  restaurantId: string | null;
  menuVersion: number | null;
  orderType: CartOrderType | null;
  items: CartLine[];
};

export type CartCatalog = {
  restaurantId: string;
  menuVersion: number;
  items: MenuItem[];
};

export type CartLimits = {
  maxItemQuantity: number;
  maxTotalItems: number;
};

export type ReconciliationNotice =
  | "restaurant-changed"
  | "menu-updated"
  | "items-updated"
  | null;

export type CartAddResult =
  | "added"
  | "invalid-selection"
  | "limit-reached"
  | "variant-conflict"
  | "locked";

export function effectiveCartLimits(limits: CartLimits): CartLimits {
  return {
    maxItemQuantity: Math.max(
      1,
      Math.min(ABSOLUTE_MAX_ITEM_QUANTITY, Math.trunc(limits.maxItemQuantity)),
    ),
    maxTotalItems: Math.max(
      1,
      Math.min(ABSOLUTE_MAX_TOTAL_ITEMS, Math.trunc(limits.maxTotalItems)),
    ),
  };
}

function modifierKey(modifier: CartModifierSelection): string {
  return `${modifier.modifierGroupId}:${modifier.optionId}:${modifier.quantity}`;
}

export function normalizedModifiers(
  modifiers: CartModifierSelection[],
): CartModifierSelection[] {
  const quantities = new Map<string, CartModifierSelection>();
  for (const modifier of modifiers) {
    if (!Number.isInteger(modifier.quantity) || modifier.quantity <= 0) continue;
    const key = `${modifier.modifierGroupId}\u0000${modifier.optionId}`;
    const previous = quantities.get(key);
    quantities.set(key, {
      modifierGroupId: modifier.modifierGroupId,
      optionId: modifier.optionId,
      quantity: (previous?.quantity ?? 0) + modifier.quantity,
    });
  }
  return [...quantities.values()].sort((left, right) =>
    modifierKey(left).localeCompare(modifierKey(right)),
  );
}

export function cartLineId(
  menuItemId: string,
  modifiers: CartModifierSelection[],
): string {
  const suffix = normalizedModifiers(modifiers).map(modifierKey).join("|");
  return suffix ? `${menuItemId}::${suffix}` : menuItemId;
}

export function totalItemQuantity(items: CartLine[]): number {
  return items.reduce((total, item) => total + item.quantity, 0);
}

export function findCatalogItem(catalog: CartCatalog, menuItemId: string): MenuItem | undefined {
  return catalog.items.find((item) => item.menuItemId === menuItemId);
}

export function modifiersAreValid(
  item: MenuItem,
  modifiers: CartModifierSelection[],
): boolean {
  const selections = normalizedModifiers(modifiers);
  const groupIds = new Set(item.modifierGroups.map((group) => group.modifierGroupId));
  if (groupIds.size !== item.modifierGroups.length || selections.length > 50) return false;
  if (selections.some((selection) => selection.quantity > 99)) return false;
  if (
    item.modifierGroups.some(
      (group) => new Set(group.options.map((option) => option.optionId)).size !== group.options.length,
    )
  ) {
    return false;
  }

  if (
    selections.some((selection) => {
      const group = item.modifierGroups.find(
        (candidate) => candidate.modifierGroupId === selection.modifierGroupId,
      );
      const option = group?.options.find(
        (candidate) => candidate.optionId === selection.optionId,
      );
      return !group || !option?.available;
    })
  ) {
    return false;
  }

  return item.modifierGroups.every((group) => {
    const selected = selections
      .filter((selection) => selection.modifierGroupId === group.modifierGroupId)
      .reduce((total, selection) => total + selection.quantity, 0);
    return selected >= group.minSelections && selected <= group.maxSelections;
  });
}

function mergeLines(items: CartLine[], limits: CartLimits): { items: CartLine[]; changed: boolean } {
  const merged = new Map<string, CartLine>();
  let remaining = limits.maxTotalItems;
  let changed = false;

  for (const item of items) {
    const previous = merged.get(item.menuItemId);
    if (previous && previous.lineId !== item.lineId) {
      changed = true;
      continue;
    }
    if (remaining <= 0 && !previous) {
      changed = true;
      continue;
    }
    if (previous) remaining += previous.quantity;
    const requested = (previous?.quantity ?? 0) + item.quantity;
    const quantity = Math.min(requested, limits.maxItemQuantity, remaining);
    if (quantity <= 0) continue;
    if (quantity !== requested || previous) changed = true;
    merged.set(item.menuItemId, { ...item, quantity });
    remaining -= quantity;
  }

  return { items: [...merged.values()], changed };
}

export function reconcileCart(
  cart: PersistedCart,
  catalog: CartCatalog,
  rawLimits: CartLimits,
): { cart: PersistedCart; notice: ReconciliationNotice } {
  const limits = effectiveCartLimits(rawLimits);
  if (cart.restaurantId && cart.restaurantId !== catalog.restaurantId) {
    return {
      cart: {
        schemaVersion: CART_SCHEMA_VERSION,
        restaurantId: catalog.restaurantId,
        menuVersion: catalog.menuVersion,
        orderType: cart.orderType,
        items: [],
      },
      notice: "restaurant-changed",
    };
  }

  let changed = false;
  const candidates: CartLine[] = [];
  for (const line of cart.items) {
    const item = findCatalogItem(catalog, line.menuItemId);
    if (!item?.available || !modifiersAreValid(item, line.modifiers)) {
      changed = true;
      continue;
    }
    const modifiers = normalizedModifiers(line.modifiers);
    const lineId = cartLineId(item.menuItemId, modifiers);
    const quantity = Math.max(1, Math.min(Math.trunc(line.quantity), limits.maxItemQuantity));
    if (lineId !== line.lineId || quantity !== line.quantity) changed = true;
    candidates.push({ lineId, menuItemId: item.menuItemId, quantity, modifiers });
  }
  const merged = mergeLines(candidates, limits);
  const items = merged.items;
  if (merged.changed || totalItemQuantity(items) !== totalItemQuantity(candidates)) changed = true;
  const menuUpdated = cart.menuVersion !== null && cart.menuVersion !== catalog.menuVersion;

  return {
    cart: {
      schemaVersion: CART_SCHEMA_VERSION,
      restaurantId: catalog.restaurantId,
      menuVersion: catalog.menuVersion,
      orderType: cart.orderType,
      items,
    },
    notice: changed ? "items-updated" : menuUpdated ? "menu-updated" : null,
  };
}

export function estimatedLineTotalMinor(line: CartLine, item: MenuItem): number {
  const modifierDelta = line.modifiers.reduce((total, selection) => {
    const option = item.modifierGroups
      .find((group) => group.modifierGroupId === selection.modifierGroupId)
      ?.options.find((candidate) => candidate.optionId === selection.optionId);
    return total + (option?.priceDeltaMinor ?? 0) * selection.quantity;
  }, 0);
  return (item.priceMinor + modifierDelta) * line.quantity;
}

export function estimatedCartTotalMinor(cart: PersistedCart, catalog: CartCatalog): number {
  return cart.items.reduce((total, line) => {
    const item = findCatalogItem(catalog, line.menuItemId);
    return item ? total + estimatedLineTotalMinor(line, item) : total;
  }, 0);
}
