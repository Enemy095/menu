"use client";

import type { AvailableOrderType, OrderLimits } from "@e-menu/api-contract/generated";
import { useEffect, useRef, useState, type KeyboardEvent, type ReactNode } from "react";

import {
  estimatedCartTotalMinor,
  findCatalogItem,
  totalItemQuantity,
  type CartCatalog,
} from "@/entities/cart/model/cart";
import { useCartStore } from "@/entities/cart/model/cart-store";
import { CartProvider } from "@/features/cart/model/cart-context";
import { CheckoutForm } from "@/features/checkout/ui/CheckoutForm";
import { formatMoney } from "@/shared/lib/format-money";

type CartExperienceProps = {
  catalog: CartCatalog;
  limits: OrderLimits;
  orderTypes: AvailableOrderType[];
  locale: string;
  currency: string;
  title: string;
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
      {props.children}
      <CartPanel {...props} />
    </CartProvider>
  );
}

function CartPanel({ catalog, limits, orderTypes, locale, currency, title }: CartExperienceProps) {
  const [open, setOpen] = useState(false);
  const [receiptVisible, setReceiptVisible] = useState(false);
  const panelRef = useRef<HTMLElement>(null);
  const closeButtonRef = useRef<HTMLButtonElement>(null);
  const mobileTriggerRef = useRef<HTMLButtonElement>(null);
  const previousFocusRef = useRef<HTMLElement | null>(null);
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
    if (open) closeButtonRef.current?.focus();
  }, [open]);

  function openCart() {
    previousFocusRef.current = document.activeElement instanceof HTMLElement
      ? document.activeElement
      : mobileTriggerRef.current;
    setOpen(true);
  }

  function closeCart() {
    setOpen(false);
    requestAnimationFrame(() => previousFocusRef.current?.focus());
  }

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

  if (!hydrated) return null;

  return (
    <>
      {count || receiptVisible ? (
        <button ref={mobileTriggerRef} className="cart-mobile-bar" type="button" onClick={openCart}>
          <span>{receiptVisible && !count ? "Заказ принят" : `${title}: ${count}`}</span>
          {count ? <strong>≈ {formatMoney(estimate, currency, locale)}</strong> : null}
        </button>
      ) : null}
      {open ? <div className="cart-backdrop" aria-hidden="true" onMouseDown={closeCart} /> : null}
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
            <p className="eyebrow">Ваш заказ</p>
            <h2 id="cart-panel-title">{title}</h2>
          </div>
          <button ref={closeButtonRef} className="cart-panel__close" type="button" onClick={closeCart} aria-label="Закрыть корзину">×</button>
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
                      <button disabled={locked} type="button" aria-label={`Уменьшить: ${item.name}`} onClick={() => setQuantity(line.lineId, line.quantity - 1, limits)}>−</button>
                      <output aria-label={`Количество: ${item.name}`}>{line.quantity}</output>
                      <button disabled={locked} type="button" aria-label={`Увеличить: ${item.name}`} onClick={() => setQuantity(line.lineId, line.quantity + 1, limits)}>+</button>
                      <button disabled={locked} className="cart-line__remove" type="button" onClick={() => remove(line.lineId)}>Удалить</button>
                    </div>
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
            <a href="#menu" onClick={() => setOpen(false)}>Перейти к меню</a>
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
