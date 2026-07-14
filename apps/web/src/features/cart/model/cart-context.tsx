"use client";

import { createContext, useContext, useEffect, type ReactNode } from "react";

import { hydrateCart, useCartStore } from "@/entities/cart/model/cart-store";
import type { CartCatalog, CartLimits } from "@/entities/cart/model/cart";

type CartContextValue = {
  catalog: CartCatalog;
  limits: CartLimits;
};

const CartContext = createContext<CartContextValue | null>(null);

export function CartProvider({
  catalog,
  limits,
  children,
}: CartContextValue & { children: ReactNode }) {
  const reconcile = useCartStore((state) => state.reconcile);

  useEffect(() => {
    hydrateCart();
    const unsubscribe = useCartStore.persist.onFinishHydration(() => reconcile(catalog, limits));
    if (useCartStore.persist.hasHydrated()) {
      useCartStore.setState({ hydrated: true });
      reconcile(catalog, limits);
    }
    return unsubscribe;
  }, [catalog, limits, reconcile]);

  return <CartContext value={{ catalog, limits }}>{children}</CartContext>;
}

export function useCartContext(): CartContextValue {
  const context = useContext(CartContext);
  if (!context) throw new Error("Cart components must be rendered inside CartProvider");
  return context;
}
