"use client";

import {
  createContext,
  useCallback,
  useContext,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from "react";

import { useOverlayScrollLock } from "@/shared/lib/overlay-scroll-lock";

type CartOverlayContextValue = {
  open: boolean;
  openCart: () => void;
  closeCart: () => void;
};

const CartOverlayContext = createContext<CartOverlayContextValue | null>(null);

export function CartOverlayProvider({ children }: { children: ReactNode }) {
  const [open, setOpen] = useState(false);
  const previousFocusRef = useRef<HTMLElement | null>(null);

  const openCart = useCallback(() => {
    previousFocusRef.current = document.activeElement instanceof HTMLElement
      ? document.activeElement
      : null;
    setOpen(true);
  }, []);

  const closeCart = useCallback(() => {
    setOpen(false);
    requestAnimationFrame(() => previousFocusRef.current?.focus());
  }, []);

  const mobileOpen =
    open &&
    (typeof window === "undefined" ||
      typeof window.matchMedia !== "function" ||
      window.matchMedia("(max-width: 1023px)").matches);
  useOverlayScrollLock(mobileOpen);

  const value = useMemo(() => ({ open, openCart, closeCart }), [closeCart, open, openCart]);

  return <CartOverlayContext value={value}>{children}</CartOverlayContext>;
}

export function useCartOverlay(): CartOverlayContextValue {
  const context = useContext(CartOverlayContext);
  if (!context) throw new Error("Cart overlay controls must be rendered inside CartOverlayProvider");
  return context;
}
