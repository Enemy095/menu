"use client";

import type { MenuItem } from "@e-menu/api-contract/generated";
import { createPortal } from "react-dom";
import { useEffect, useId, useMemo, useRef, useState } from "react";

import {
  modifiersAreValid,
  type CartModifierSelection,
} from "@/entities/cart/model/cart";
import { useCartStore } from "@/entities/cart/model/cart-store";
import { useBusinessAvailability } from "@/entities/restaurant/model/business-availability";
import { useOptionalCartContext } from "@/features/cart/model/cart-context";
import { formatMoney } from "@/shared/lib/format-money";
import { useOverlayScrollLock } from "@/shared/lib/overlay-scroll-lock";

type MenuItemCardProps = {
  item: MenuItem;
  categoryName?: string;
  displayName: string;
  imageUrl?: string;
  locale: string;
  currency: string;
  orderingEnabled: boolean;
};

function selectionKey(modifierGroupId: string, optionId: string): string {
  return `${modifierGroupId}::${optionId}`;
}

export function MenuItemCard({
  item,
  categoryName,
  displayName,
  imageUrl,
  locale,
  currency,
  orderingEnabled,
}: MenuItemCardProps) {
  const cartContext = useOptionalCartContext();
  const availability = useBusinessAvailability();
  const add = useCartStore((state) => state.add);
  const locked = useCartStore((state) => state.locked);
  const [open, setOpen] = useState(false);
  const [quantity, setQuantity] = useState(1);
  const [optionQuantities, setOptionQuantities] = useState<Record<string, number>>({});
  const [message, setMessage] = useState("");
  const closeButtonRef = useRef<HTMLButtonElement>(null);
  const previousFocusRef = useRef<HTMLElement | null>(null);
  const titleId = useId();
  const descriptionId = useId();

  const modifiers = useMemo<CartModifierSelection[]>(
    () =>
      Object.entries(optionQuantities)
        .filter(([, selectedQuantity]) => selectedQuantity > 0)
        .map(([key, selectedQuantity]) => {
          const [modifierGroupId = "", optionId = ""] = key.split("::");
          return { modifierGroupId, optionId, quantity: selectedQuantity };
        }),
    [optionQuantities],
  );
  const modifierPriceMinor = useMemo(
    () =>
      modifiers.reduce((total, selection) => {
        const option = item.modifierGroups
          .find((group) => group.modifierGroupId === selection.modifierGroupId)
          ?.options.find((candidate) => candidate.optionId === selection.optionId);
        return total + (option?.priceDeltaMinor ?? 0) * selection.quantity;
      }, 0),
    [item.modifierGroups, modifiers],
  );
  const totalMinor = (item.priceMinor + modifierPriceMinor) * quantity;
  useOverlayScrollLock(open);

  useEffect(() => {
    if (!open) return;
    closeButtonRef.current?.focus();

    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape") setOpen(false);
    }

    document.addEventListener("keydown", handleKeyDown);
    return () => {
      document.removeEventListener("keydown", handleKeyDown);
      requestAnimationFrame(() => previousFocusRef.current?.focus());
    };
  }, [open]);

  function openDialog() {
    if (!orderingEnabled || !item.available) return;
    previousFocusRef.current = document.activeElement instanceof HTMLElement
      ? document.activeElement
      : null;
    setQuantity(1);
    setMessage("");
    setOpen(true);
  }

  function closeDialog() {
    setOpen(false);
  }

  function addSelection() {
    if (availability.blocked) { setMessage(availability.message); return; }
    if (!modifiersAreValid(item, modifiers)) {
      setMessage("Проверьте обязательные добавки и их количество.");
      return;
    }

    if (!cartContext) return;
    const result = add(
      cartContext.catalog,
      cartContext.limits,
      item.menuItemId,
      modifiers,
      quantity,
    );
    if (result === "added") {
      closeDialog();
      return;
    }
    setMessage(
      result === "variant-conflict"
        ? "Эта позиция уже добавлена с другими добавками."
        : result === "locked"
          ? "Дождитесь завершения отправки заказа."
          : result === "limit-reached"
            ? "Для выбранного количества достигнут лимит корзины."
            : "Проверьте выбранные добавки.",
    );
  }

  const dialog = open ? (
    <div className="dish-dialog-layer" role="presentation" onPointerDown={closeDialog}>
      <section
        className="dish-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        aria-describedby={item.description ? descriptionId : undefined}
        onPointerDown={(event) => event.stopPropagation()}
      >
        <button
          ref={closeButtonRef}
          className="dish-dialog__close"
          type="button"
          aria-label="Закрыть"
          onClick={closeDialog}
        >
          ×
        </button>
        {imageUrl ? (
          <div className="dish-dialog__visual">
            {/* The API exposes immutable asset URLs; local generated images use the same slot. */}
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img src={imageUrl} alt={item.name} />
          </div>
        ) : null}
        <div className="dish-dialog__content">
          {categoryName ? <p className="dish-dialog__category">{categoryName}</p> : null}
          <h2 id={titleId}>{displayName}</h2>
          {item.description ? <p id={descriptionId} className="dish-dialog__description">{item.description}</p> : null}
          {item.modifierGroups.map((group) => (
            <fieldset className="dish-dialog__options" key={group.modifierGroupId}>
              <legend>
                <span>{group.name}</span>
                <small>{group.minSelections ? "Обязательно" : "По желанию"}</small>
              </legend>
              {group.options.map((option) => {
                const key = selectionKey(group.modifierGroupId, option.optionId);
                const selectedQuantity = optionQuantities[key] ?? 0;
                return (
                  <label className="dish-dialog__option" key={option.optionId}>
                    <input
                      type="checkbox"
                      checked={selectedQuantity > 0}
                      disabled={!option.available || locked || availability.blocked}
                      onChange={(event) =>
                        setOptionQuantities((current) => ({
                          ...current,
                          [key]: event.target.checked ? 1 : 0,
                        }))
                      }
                    />
                    <span>{option.name}</span>
                    {option.priceDeltaMinor ? (
                      <small>+ {formatMoney(option.priceDeltaMinor, currency, locale)}</small>
                    ) : null}
                  </label>
                );
              })}
            </fieldset>
          ))}
          {availability.blocked || message ? <p className="dish-dialog__message" role="status">{availability.blocked ? availability.message : message}</p> : null}
          {availability.closedToday && availability.reopening ? <p className="dish-dialog__message">{availability.reopening}</p> : null}
        </div>
        <footer className="dish-dialog__footer">
          <div className="dish-dialog__quantity" role="group" aria-label={`Количество: ${item.name}`}>
            <button
              type="button"
              aria-label="Уменьшить количество"
              disabled={quantity <= 1 || locked || availability.blocked}
              onClick={() => setQuantity((current) => Math.max(1, current - 1))}
            >
              −
            </button>
            <output aria-live="polite">{quantity}</output>
            <button
              type="button"
              aria-label="Увеличить количество"
              disabled={quantity >= (cartContext?.limits.maxItemQuantity ?? 99) || locked || availability.blocked}
              onClick={() =>
                setQuantity((current) =>
                  Math.min(cartContext?.limits.maxItemQuantity ?? 99, current + 1),
                )
              }
            >
              +
            </button>
          </div>
          <button
            className="button dish-dialog__add"
            type="button"
            disabled={locked || availability.blocked}
            aria-busy={locked || undefined}
            onClick={addSelection}
          >
            <span>{locked ? "Подождите…" : "Добавить"}</span>
            <strong>{formatMoney(totalMinor, currency, locale)}</strong>
          </button>
        </footer>
      </section>
    </div>
  ) : null;

  return (
    <li className="menu-card" data-menu-category={item.categoryId}>
      {orderingEnabled && item.available ? (
        <button className="menu-card__open" type="button" aria-label={`Открыть: ${item.name}`} onClick={openDialog} />
      ) : null}
      {imageUrl ? (
        <div className="menu-card__visual">
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img className="menu-card__image" src={imageUrl} alt={item.name} loading="lazy" />
        </div>
      ) : null}
      <div className="menu-card__body">
        {categoryName ? <p className="menu-card__category">{categoryName}</p> : null}
        <h3>{displayName}</h3>
        {item.description ? <p>{item.description}</p> : null}
        {!item.available ? <span className="availability">Временно недоступно</span> : null}
        <div className="menu-card__footer">
          <strong>{formatMoney(item.priceMinor, currency, locale)}</strong>
          {orderingEnabled && item.available ? (
            <div className="menu-card__order">
              <button className="button button--compact" type="button" onClick={openDialog} disabled={locked || availability.blocked} title={availability.message || undefined}>
                <span aria-hidden="true">+</span>
                <span className="sr-only">Выбрать количество: {item.name}</span>
              </button>
            </div>
          ) : null}
        </div>
      </div>
      {typeof document === "undefined"
        ? null
        : createPortal(dialog, document.body)}
    </li>
  );
}
