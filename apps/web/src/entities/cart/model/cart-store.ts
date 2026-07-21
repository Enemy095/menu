"use client";

import { create } from "zustand";
import { persist, type PersistStorage, type StorageValue } from "zustand/middleware";
import { z } from "zod";

import {
  CART_SCHEMA_VERSION,
  CART_STORAGE_KEY,
  cartLineId,
  effectiveCartLimits,
  modifiersAreValid,
  normalizedModifiers,
  reconcileCart,
  totalItemQuantity,
  type CartCatalog,
  type CartAddResult,
  type CartLimits,
  type CartLine,
  type CartModifierSelection,
  type CartOrderType,
  type PersistedCart,
  type ReconciliationNotice,
} from "./cart";

const modifierSchema = z.object({
  modifierGroupId: z.string().min(1),
  optionId: z.string().min(1),
  quantity: z.number().int().min(1).max(99),
});

const lineSchema = z.object({
  lineId: z.string().min(1),
  menuItemId: z.string().min(1),
  quantity: z.number().int().min(1).max(99),
  modifiers: z.array(modifierSchema).max(50),
});

const persistedCartSchema = z.object({
  schemaVersion: z.literal(CART_SCHEMA_VERSION),
  restaurantId: z.string().min(1).nullable(),
  menuVersion: z.number().int().positive().nullable(),
  orderType: z.enum(["delivery", "pickup", "preorder"]).nullable(),
  items: z.array(lineSchema).max(200),
});

type PersistedSlice = PersistedCart;

type CartStore = PersistedCart & {
  hydrated: boolean;
  locked: boolean;
  notice: ReconciliationNotice;
  revision: number;
  reconcile: (catalog: CartCatalog, limits: CartLimits) => void;
  add: (
    catalog: CartCatalog,
    limits: CartLimits,
    menuItemId: string,
    modifiers: CartModifierSelection[],
    quantity?: number,
  ) => CartAddResult;
  setQuantity: (lineId: string, quantity: number, limits: CartLimits) => void;
  remove: (lineId: string) => void;
  setOrderType: (orderType: CartOrderType) => void;
  clear: () => void;
  beginSubmission: () => { items: CartLine[]; menuVersion: number; revision: number } | null;
  failSubmission: (revision: number) => void;
  completeSubmission: (revision: number) => boolean;
  dismissNotice: () => void;
};

const emptyCart: PersistedCart = {
  schemaVersion: CART_SCHEMA_VERSION,
  restaurantId: null,
  menuVersion: null,
  orderType: null,
  items: [],
};

const memoryStorage = new Map<string, string>();

function reportStorageFallback(operation: string): void {
  if (typeof window === "undefined") return;
  document.documentElement.dataset.cartStorage = "memory";
  window.dispatchEvent(
    new CustomEvent("e-menu:cart-storage-fallback", { detail: { operation } }),
  );
}

function localStorageOrNull(operation: string): Storage | null {
  try {
    return window.localStorage;
  } catch {
    reportStorageFallback(operation);
    return null;
  }
}

function browserStorage(): PersistStorage<PersistedSlice> {
  return {
    getItem(name): StorageValue<PersistedSlice> | null {
      let raw: string | null = null;
      try {
        raw = localStorageOrNull("get")?.getItem(name) ?? memoryStorage.get(name) ?? null;
      } catch {
        reportStorageFallback("get");
        raw = memoryStorage.get(name) ?? null;
      }
      if (!raw) return null;
      try {
        const envelope = z
          .object({ state: persistedCartSchema, version: z.literal(CART_SCHEMA_VERSION) })
          .parse(JSON.parse(raw));
        return envelope;
      } catch {
        try {
          localStorageOrNull("remove-corrupt")?.removeItem(name);
        } catch {
          reportStorageFallback("remove-corrupt");
        }
        memoryStorage.delete(name);
        return null;
      }
    },
    setItem(name, value): void {
      const serialized = JSON.stringify(value);
      memoryStorage.set(name, serialized);
      try {
        const storage = localStorageOrNull("set");
        storage?.setItem(name, serialized);
        if (storage) document.documentElement.dataset.cartStorage = "local";
      } catch {
        reportStorageFallback("set");
      }
    },
    removeItem(name): void {
      memoryStorage.delete(name);
      try {
        localStorageOrNull("remove")?.removeItem(name);
      } catch {
        reportStorageFallback("remove");
      }
    },
  };
}

function persisted(state: CartStore): PersistedSlice {
  return {
    schemaVersion: CART_SCHEMA_VERSION,
    restaurantId: state.restaurantId,
    menuVersion: state.menuVersion,
    orderType: state.orderType,
    items: state.items,
  };
}

export const useCartStore = create<CartStore>()(
  persist(
    (set, get) => ({
      ...emptyCart,
      hydrated: false,
      locked: false,
      notice: null,
      revision: 0,
      reconcile(catalog, limits) {
        if (get().locked) return;
        const result = reconcileCart(persisted(get()), catalog, limits);
        set({ ...result.cart, notice: result.notice, revision: get().revision + 1 });
      },
      add(catalog, rawLimits, menuItemId, rawModifiers, rawQuantity = 1) {
        if (get().locked) return "locked";
        const limits = effectiveCartLimits(rawLimits);
        const reconciled = reconcileCart(persisted(get()), catalog, limits);
        const item = catalog.items.find((candidate) => candidate.menuItemId === menuItemId);
        if (!item?.available || !modifiersAreValid(item, rawModifiers)) return "invalid-selection";
        const requestedQuantity = Math.trunc(rawQuantity);
        if (!Number.isSafeInteger(requestedQuantity) || requestedQuantity < 1) {
          return "invalid-selection";
        }
        const modifiers = normalizedModifiers(rawModifiers);
        const lineId = cartLineId(menuItemId, modifiers);
        const existingItem = reconciled.cart.items.find((line) => line.menuItemId === menuItemId);
        if (existingItem && existingItem.lineId !== lineId) return "variant-conflict";
        const existing = existingItem;
        if (
          totalItemQuantity(reconciled.cart.items) + requestedQuantity > limits.maxTotalItems ||
          (existing?.quantity ?? 0) + requestedQuantity > limits.maxItemQuantity
        ) {
          set({ ...reconciled.cart, notice: reconciled.notice });
          return "limit-reached";
        }
        const items = existing
          ? reconciled.cart.items.map((line) =>
              line.lineId === lineId
                ? { ...line, quantity: line.quantity + requestedQuantity }
                : line,
            )
          : [
              ...reconciled.cart.items,
              { lineId, menuItemId, quantity: requestedQuantity, modifiers } satisfies CartLine,
            ];
        set({
          ...reconciled.cart,
          items,
          notice: reconciled.notice,
          revision: get().revision + 1,
        });
        return "added";
      },
      setQuantity(lineId, requested, rawLimits) {
        if (get().locked) return;
        const limits = effectiveCartLimits(rawLimits);
        const current = get();
        const line = current.items.find((item) => item.lineId === lineId);
        if (!line) return;
        const withoutLine = totalItemQuantity(current.items) - line.quantity;
        const quantity = Math.max(
          0,
          Math.min(Math.trunc(requested), limits.maxItemQuantity, limits.maxTotalItems - withoutLine),
        );
        set({
          items: quantity
            ? current.items.map((item) => (item.lineId === lineId ? { ...item, quantity } : item))
            : current.items.filter((item) => item.lineId !== lineId),
          revision: current.revision + 1,
        });
      },
      remove(lineId) {
        if (get().locked) return;
        set((state) => ({
          items: state.items.filter((item) => item.lineId !== lineId),
          revision: state.revision + 1,
        }));
      },
      setOrderType(orderType) {
        if (get().locked) return;
        set((state) => ({ orderType, revision: state.revision + 1 }));
      },
      clear() {
        if (get().locked) return;
        const current = get();
        set({
          ...emptyCart,
          restaurantId: current.restaurantId,
          menuVersion: current.menuVersion,
          orderType: current.orderType,
          hydrated: true,
          revision: current.revision + 1,
        });
      },
      dismissNotice() {
        set({ notice: null });
      },
      beginSubmission() {
        const current = get();
        if (current.locked || !current.menuVersion || !current.items.length) return null;
        set({ locked: true });
        return {
          items: current.items.map((item) => ({
            ...item,
            modifiers: item.modifiers.map((modifier) => ({ ...modifier })),
          })),
          menuVersion: current.menuVersion,
          revision: current.revision,
        };
      },
      failSubmission(revision) {
        const current = get();
        if (current.locked && current.revision === revision) set({ locked: false });
      },
      completeSubmission(revision) {
        const current = get();
        if (!current.locked || current.revision !== revision) {
          if (current.locked) set({ locked: false });
          return false;
        }
        set({
          ...emptyCart,
          restaurantId: current.restaurantId,
          menuVersion: current.menuVersion,
          orderType: current.orderType,
          hydrated: true,
          locked: false,
          revision: current.revision + 1,
        });
        return true;
      },
    }),
    {
      name: CART_STORAGE_KEY,
      version: CART_SCHEMA_VERSION,
      storage: typeof window === "undefined" ? undefined : browserStorage(),
      partialize: persisted,
      skipHydration: true,
      merge(persistedState, currentState) {
        const parsed = persistedCartSchema.safeParse(persistedState);
        return parsed.success ? { ...currentState, ...parsed.data } : currentState;
      },
      onRehydrateStorage: () => () => {
        useCartStore.setState({ hydrated: true, locked: false });
        if (typeof window !== "undefined") {
          document.documentElement.dataset.cartHydrated = "true";
          window.dispatchEvent(new CustomEvent("e-menu:cart-hydrated"));
        }
      },
    },
  ),
);

export async function hydrateCart(): Promise<void> {
  try {
    if (!useCartStore.persist.hasHydrated()) await useCartStore.persist.rehydrate();
  } finally {
    useCartStore.setState({ hydrated: true, locked: false });
    if (typeof window !== "undefined") {
      document.documentElement.dataset.cartHydrated = "true";
      window.dispatchEvent(new CustomEvent("e-menu:cart-hydrated"));
    }
  }
}

export function resetCartStoreForTests(): void {
  memoryStorage.clear();
  useCartStore.setState({ ...emptyCart, hydrated: false, locked: false, notice: null, revision: 0 });
}
