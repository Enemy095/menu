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
    let active = true;
    void hydrateCart().then(() => {
      if (active) reconcile(catalog, limits);
    });
    return () => {
      active = false;
    };
  }, [catalog, limits, reconcile]);

  return <CartContext value={{ catalog, limits }}>{children}</CartContext>;
}

export function useCartContext(): CartContextValue {
  const context = useContext(CartContext);
  if (!context) throw new Error("Cart components must be rendered inside CartProvider");
  return context;
}

export function useOptionalCartContext(): CartContextValue | null {
  return useContext(CartContext);
}
