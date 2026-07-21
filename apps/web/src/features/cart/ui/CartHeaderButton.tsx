"use client";

import { totalItemQuantity } from "@/entities/cart/model/cart";
import { useCartStore } from "@/entities/cart/model/cart-store";
import { useCartOverlay } from "@/features/cart/model/cart-overlay-context";

export function CartHeaderButton() {
  const { openCart } = useCartOverlay();
  const hydrated = useCartStore((state) => state.hydrated);
  const items = useCartStore((state) => state.items);
  const count = totalItemQuantity(items);

  return (
    <button
      className="header-cart"
      type="button"
      onClick={openCart}
      disabled={!hydrated}
      aria-busy={!hydrated || undefined}
      aria-label={hydrated ? "Открыть корзину" : "Загрузка корзины"}
    >
      <svg viewBox="0 0 24 24" aria-hidden="true">
        <path d="M3 4h2l2.2 10.1a2 2 0 0 0 2 1.6h7.7a2 2 0 0 0 1.9-1.4L21 7H7" />
        <circle cx="10" cy="20" r="1.25" />
        <circle cx="18" cy="20" r="1.25" />
      </svg>
      <span className="header-cart__badge" aria-hidden="true">{count}</span>
    </button>
  );
}
