"use client";

import type { AvailableOrderType, OrderLimits } from "@e-menu/api-contract/generated";
import { useEffect, useRef, useState, type KeyboardEvent, type ReactNode } from "react";

import {
  estimatedCartTotalMinor,
  estimatedLineTotalMinor,
  findCatalogItem,
  totalItemQuantity,
  type CartCatalog,
} from "@/entities/cart/model/cart";
import { useCartStore } from "@/entities/cart/model/cart-store";
import { CartProvider } from "@/features/cart/model/cart-context";
import { CartOverlayProvider, useCartOverlay } from "@/features/cart/model/cart-overlay-context";
import { CheckoutForm } from "@/features/checkout/ui/CheckoutForm";
import { formatMoney } from "@/shared/lib/format-money";

type CartExperienceProps = {
  catalog: CartCatalog;
  limits: OrderLimits;
  orderTypes: AvailableOrderType[];
  locale: string;
  currency: string;
  title: string;
  header?: ReactNode;
  children: ReactNode;
};

const FOCUSABLE_SELECTOR = [
  "a[href]",
  "button:not(:disabled)",
  "input:not(:disabled)",
  "select:not(:disabled)",
  "textarea:not(:disabled)",
  "summary",
  '[tabindex]:not([tabindex="-1"])',
].join(", ");

function isVisibleAndFocusable(element: HTMLElement): boolean {
  if (element.matches(":disabled") || element.tabIndex < 0) return false;

  for (let current: HTMLElement | null = element; current; current = current.parentElement) {
    if (
      current.hidden ||
      current.hasAttribute("inert") ||
      current.getAttribute("aria-hidden") === "true"
    ) {
      return false;
    }
    const style = window.getComputedStyle(current);
    if (
      style.display === "none" ||
      style.visibility === "hidden" ||
      style.visibility === "collapse"
    ) {
      return false;
    }
  }

  return true;
}

export function CartExperience(props: CartExperienceProps) {
  return (
    <CartProvider catalog={props.catalog} limits={props.limits}>
      <CartOverlayProvider>
        {props.header}
        <main className="desktop-layout" id="main-content">
          {props.children}
          <CartPanel {...props} />
        </main>
      </CartOverlayProvider>
    </CartProvider>
  );
}

function CartPanel({ catalog, limits, orderTypes, locale, currency, title }: CartExperienceProps) {
  const { open, openCart, closeCart } = useCartOverlay();
  const [receiptVisible, setReceiptVisible] = useState(false);
  const panelRef = useRef<HTMLElement>(null);
  const closeButtonRef = useRef<HTMLButtonElement>(null);
  const hydrated = useCartStore((state) => state.hydrated);
  const locked = useCartStore((state) => state.locked);
  const items = useCartStore((state) => state.items);
  const notice = useCartStore((state) => state.notice);
  const dismissNotice = useCartStore((state) => state.dismissNotice);
  const setQuantity = useCartStore((state) => state.setQuantity);
  const remove = useCartStore((state) => state.remove);
  const count = totalItemQuantity(items);
  const estimate = estimatedCartTotalMinor(
    {
      schemaVersion: 1,
      restaurantId: catalog.restaurantId,
      menuVersion: catalog.menuVersion,
      orderType: null,
      items,
    },
    catalog,
  );

  useEffect(() => {
    if (!open) return;
    if (panelRef.current) panelRef.current.scrollTop = 0;
    closeButtonRef.current?.focus();
  }, [open]);

  function handleDialogKeyDown(event: KeyboardEvent<HTMLElement>) {
    if (!open) return;
    if (event.key === "Escape") {
      event.preventDefault();
      closeCart();
      return;
    }
    if (event.key !== "Tab") return;
    const focusable = [
      ...(panelRef.current?.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR) ?? []),
    ].filter(isVisibleAndFocusable);
    const first = focusable[0];
    const last = focusable.at(-1);
    if (!first || !last) return;
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first.focus();
    }
  }

  function handleReceiptChange(visible: boolean) {
    setReceiptVisible(visible);
    if (!visible && open) closeCart();
  }

  return (
    <>
      <button
        className={`cart-mobile-bar${hydrated ? "" : " cart-mobile-bar--loading"}`}
        type="button"
        onClick={openCart}
        disabled={!hydrated}
        aria-busy={!hydrated || undefined}
        aria-label={
          !hydrated
            ? "Загрузка корзины"
            : receiptVisible && !count
              ? "Заказ в разработке"
              : `${title}: ${count}`
        }
      >
        <span className="cart-mobile-bar__count" aria-hidden="true">
          <svg viewBox="0 0 24 24">
            <path d="M3 4h2l2.2 10.1a2 2 0 0 0 2 1.6h7.7a2 2 0 0 0 1.9-1.4L21 7H7" />
            <circle cx="10" cy="20" r="1.25" />
            <circle cx="18" cy="20" r="1.25" />
          </svg>
        </span>
        <span className="cart-mobile-bar__label" aria-hidden="true">
          {!hydrated
            ? "Загрузка корзины…"
            : receiptVisible && !count
              ? "Заказ в разработке"
              : "Ваш заказ"}
        </span>
        {hydrated ? (
          <strong className="cart-mobile-bar__total" aria-hidden="true">
            {formatMoney(estimate, currency, locale)}
          </strong>
        ) : null}
      </button>
      {open ? <div className="cart-backdrop" aria-hidden="true" onPointerDown={closeCart} /> : null}
      <aside
        ref={panelRef}
        className={`cart-panel${open ? " cart-panel--open" : ""}`}
        aria-labelledby="cart-panel-title"
        aria-modal={open || undefined}
        aria-busy={locked || undefined}
        role={open ? "dialog" : "region"}
        onKeyDown={handleDialogKeyDown}
      >
        <header className="cart-panel__header">
          <div>
            <h2 id="cart-panel-title">{title}</h2>
          </div>
          <button
            ref={closeButtonRef}
            className="cart-panel__close"
            type="button"
            onClick={closeCart}
            aria-label="Закрыть корзину"
          >
            <svg aria-hidden="true" viewBox="0 0 24 24">
              <path d="M7 7l10 10M17 7 7 17" />
            </svg>
          </button>
        </header>
        {notice ? (
          <div className="cart-notice" role="status">
            <span>
              {notice === "restaurant-changed"
                ? "Корзина очищена: открыто другое заведение."
                : notice === "menu-updated"
                  ? "Корзина проверена по новой версии меню."
                  : "Недоступные позиции или добавки удалены из корзины."}
            </span>
            <button type="button" onClick={dismissNotice} aria-label="Скрыть уведомление">×</button>
          </div>
        ) : null}
        {items.length ? (
          <>
            <ul className="cart-lines">
              {items.map((line) => {
                const item = findCatalogItem(catalog, line.menuItemId);
                if (!item) return null;
                return (
                  <li key={line.lineId}>
                    <div className="cart-line__title">
                      <strong>{item.name}</strong>
                      {line.modifiers.length ? (
                        <small>
                          {line.modifiers.map((selection) =>
                            item.modifierGroups
                              .find((group) => group.modifierGroupId === selection.modifierGroupId)
                              ?.options.find((option) => option.optionId === selection.optionId)?.name,
                          ).filter(Boolean).join(", ")}
                        </small>
                      ) : null}
                    </div>
                    <div className="cart-line__controls">
                      <div className="cart-line__quantity">
                        <button disabled={locked} type="button" aria-label={`Уменьшить: ${item.name}`} onClick={() => setQuantity(line.lineId, line.quantity - 1, limits)}>−</button>
                        <output aria-label={`Количество: ${item.name}`}>{line.quantity}</output>
                        <button disabled={locked} type="button" aria-label={`Увеличить: ${item.name}`} onClick={() => setQuantity(line.lineId, line.quantity + 1, limits)}>+</button>
                      </div>
                      <button
                        disabled={locked}
                        className="cart-line__remove"
                        type="button"
                        onClick={() => remove(line.lineId)}
                        aria-label={`Удалить: ${item.name}`}
                        title="Удалить"
                      >
                        <svg aria-hidden="true" viewBox="0 0 24 24">
                          <path d="M4 7h16M9 7V4h6v3m-9 0 1 13h10l1-13M10 11v5m4-5v5" />
                        </svg>
                      </button>
                    </div>
                    <strong className="cart-line__total">
                      {formatMoney(estimatedLineTotalMinor(line, item), currency, locale)}
                    </strong>
                  </li>
                );
              })}
            </ul>
            <div className="cart-estimate">
              <span>Предварительно</span>
              <strong>≈ {formatMoney(estimate, currency, locale)}</strong>
            </div>
          </>
        ) : !receiptVisible ? (
          <div className="cart-empty" role="status">
            <p>Корзина пока пуста.</p>
            <a href="#menu" onClick={closeCart}>Перейти к меню</a>
          </div>
        ) : null}
        {items.length || receiptVisible ? (
          <CheckoutForm
            items={items}
            orderTypes={orderTypes}
            limits={limits}
            locale={locale}
            onReceiptChange={handleReceiptChange}
          />
        ) : null}
      </aside>
    </>
  );
}
