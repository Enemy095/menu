"use client";

import { useMemo, useState } from "react";

import {
  modifiersAreValid,
  type CartModifierSelection,
} from "@/entities/cart/model/cart";
import { useCartStore } from "@/entities/cart/model/cart-store";
import { useBusinessAvailability } from "@/entities/restaurant/model/business-availability";
import { useCartContext } from "@/features/cart/model/cart-context";
import { formatMoney } from "@/shared/lib/format-money";

type AddToCartControlProps = {
  menuItemId: string;
  locale: string;
  currency: string;
};

export function AddToCartControl({ menuItemId, locale, currency }: AddToCartControlProps) {
  const { catalog, limits } = useCartContext();
  const availability = useBusinessAvailability();
  const add = useCartStore((state) => state.add);
  const locked = useCartStore((state) => state.locked);
  const [quantities, setQuantities] = useState<Record<string, number>>({});
  const [message, setMessage] = useState("");
  const item = catalog.items.find((candidate) => candidate.menuItemId === menuItemId);
  const modifiers = useMemo<CartModifierSelection[]>(
    () =>
      Object.entries(quantities)
        .filter(([, quantity]) => quantity > 0)
        .map(([key, quantity]) => {
          const [modifierGroupId = "", optionId = ""] = key.split("::");
          return { modifierGroupId, optionId, quantity };
        }),
    [quantities],
  );

  if (!item?.available) return null;
  if (availability.blocked) return <p className="form-message" role="status">{availability.message}</p>;

  function addSelection() {
    if (availability.blocked) { setMessage(availability.message); return; }
    if (!item || !modifiersAreValid(item, modifiers)) {
      setMessage("Проверьте обязательные добавки и их количество.");
      return;
    }
    const result = add(catalog, limits, item.menuItemId, modifiers);
    setMessage(
      result === "added"
        ? "Добавлено в корзину"
        : result === "variant-conflict"
          ? "Эта позиция уже добавлена с другими добавками. Удалите её из корзины, чтобы изменить выбор."
          : result === "locked"
            ? "Дождитесь завершения отправки заказа."
            : result === "limit-reached"
              ? "Достигнут лимит корзины"
              : "Проверьте выбранные добавки.",
    );
  }

  if (!item.modifierGroups.length) {
    return (
      <div className="menu-card__order">
        <button className="button button--compact" type="button" onClick={addSelection} disabled={locked}>
          Добавить
        </button>
        <span className="sr-only" aria-live="polite">{message}</span>
      </div>
    );
  }

  return (
    <details className="modifier-picker">
      <summary>Выбрать добавки</summary>
      <div className="modifier-picker__content">
        {item.modifierGroups.map((group) => (
          <fieldset key={group.modifierGroupId}>
            <legend>
              {group.name} <small>от {group.minSelections} до {group.maxSelections}</small>
            </legend>
            {group.options.map((option) => {
              const key = `${group.modifierGroupId}::${option.optionId}`;
              const quantity = quantities[key] ?? 0;
              return (
                <div className="modifier-option" key={option.optionId}>
                  <label>
                    <input
                      type="checkbox"
                      checked={quantity > 0}
                      disabled={!option.available || locked}
                      onChange={(event) =>
                        setQuantities((current) => ({
                          ...current,
                          [key]: event.target.checked ? 1 : 0,
                        }))
                      }
                    />
                    <span>{option.name}</span>
                    {option.priceDeltaMinor ? (
                      <small>+{formatMoney(option.priceDeltaMinor, currency, locale)}</small>
                    ) : null}
                  </label>
                  {quantity > 0 && group.maxSelections > 1 ? (
                    <input
                      aria-label={`Количество: ${option.name}`}
                      type="number"
                      min={1}
                      max={Math.min(99, group.maxSelections)}
                      value={quantity}
                      disabled={locked}
                      onChange={(event) =>
                        setQuantities((current) => ({
                          ...current,
                          [key]: Math.max(1, Math.min(group.maxSelections, Number(event.target.value))),
                        }))
                      }
                    />
                  ) : null}
                </div>
              );
            })}
          </fieldset>
        ))}
        <button className="button button--compact" type="button" onClick={addSelection} disabled={locked}>
          Добавить
        </button>
        <p className="form-message" aria-live="polite">{message}</p>
      </div>
    </details>
  );
}
