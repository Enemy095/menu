import axe from "axe-core";
import { OrderType } from "@e-menu/api-contract/generated";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { CART_STORAGE_KEY } from "@/entities/cart/model/cart";
import { resetCartStoreForTests, useCartStore } from "@/entities/cart/model/cart-store";
import { AddToCartControl } from "@/features/cart/ui/AddToCartControl";
import { CartHeaderButton } from "@/features/cart/ui/CartHeaderButton";
import { formatMoney } from "@/shared/lib/format-money";

import { CartExperience } from "./CartExperience";

const { submitMock } = vi.hoisted(() => ({ submitMock: vi.fn() }));

vi.mock("@/shared/api/orders-api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/shared/api/orders-api")>()),
  submitPublicOrder: submitMock,
}));

const catalog = {
  restaurantId: "restaurant",
  menuVersion: 3,
  items: [
    {
      menuItemId: "salad",
      categoryId: "starters",
      name: "Салат",
      priceMinor: 49000,
      available: true,
      sortOrder: 1,
      modifierGroups: [],
    },
  ],
};

const limits = {
  maxItemQuantity: 10,
  maxTotalItems: 50,
  maxNameLength: 80,
  maxPhoneLength: 32,
  maxAddressLength: 240,
  maxCommentLength: 500,
  delayedOrderConfirmationMinutes: 10,
};

function renderCart() {
  return render(
    <CartExperience
      catalog={catalog}
      limits={limits}
      orderTypes={[{ code: OrderType.pickup, label: "Самовывоз", isDefault: true }]}
      locale="ru-RU"
      currency="RUB"
      title="Корзина"
      header={<CartHeaderButton />}
    >
      <AddToCartControl menuItemId="salad" locale="ru-RU" currency="RUB" />
    </CartExperience>,
  );
}

describe("CartExperience", () => {
  beforeEach(() => {
    window.localStorage.clear();
    resetCartStoreForTests();
    submitMock.mockReset();
  });

  it("opens the same mobile cart from the header badge and bottom summary", async () => {
    const user = userEvent.setup();
    renderCart();

    const headerTrigger = screen.getByRole("button", { name: "Открыть корзину" });
    expect(headerTrigger).toHaveTextContent("0");

    await user.click(screen.getByRole("button", { name: "Добавить" }));
    await waitFor(() => expect(headerTrigger).toHaveTextContent("1"));
    const bottomTrigger = await screen.findByRole("button", { name: "Корзина: 1" });

    await user.click(headerTrigger);
    expect(screen.getByRole("dialog", { name: "Корзина" })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Закрыть корзину" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());

    await user.click(bottomTrigger);
    expect(screen.getByRole("dialog", { name: "Корзина" })).toBeInTheDocument();
  });

  it("keeps the idempotency key stable while retrying the same semantic draft", async () => {
    const user = userEvent.setup();
    submitMock
      .mockRejectedValueOnce(new TypeError("network"))
      .mockResolvedValueOnce({
        order: {
          orderId: "0f40f8a4-2612-4df2-a5e8-812de01740c0",
          orderNumber: "A-42",
          status: "new",
          serverTotalMinor: 49000,
          currency: "RUB",
          serverReceivedAt: new Date("2026-07-14T12:00:00Z"),
          needsConfirmation: false,
        },
        replayed: false,
      });
    const view = renderCart();

    await user.click(screen.getByRole("button", { name: "Добавить" }));
    await waitFor(() => expect(screen.getByText("Предварительно")).toBeInTheDocument());
    await user.type(screen.getByLabelText("Имя"), "Анна");
    await user.type(screen.getByLabelText("Телефон"), "+79990000000");
    await user.click(screen.getByRole("button", { name: "Оформить заказ" }));
    await screen.findByRole("alert");
    await user.click(screen.getByRole("button", { name: "Оформить заказ" }));

    await screen.findByRole("heading", { name: "Заказ № A-42" });
    expect(useCartStore.getState().items).toEqual([]);
    expect(JSON.parse(window.localStorage.getItem(CART_STORAGE_KEY) ?? "{}").state.items).toEqual([]);
    expect(submitMock).toHaveBeenCalledTimes(2);
    expect(submitMock.mock.calls[0]?.[0]).toBe(submitMock.mock.calls[1]?.[0]);
    expect(submitMock.mock.calls[0]?.[1]).not.toHaveProperty("clientTotal");

    view.unmount();
    resetCartStoreForTests();
    renderCart();
    await waitFor(() => expect(screen.queryByRole("button", { name: "Оформить заказ" })).not.toBeInTheDocument());
    expect(submitMock).toHaveBeenCalledTimes(2);
  });

  it("renders the authoritative response currency when it differs from site configuration", async () => {
    const user = userEvent.setup();
    submitMock.mockResolvedValue({
      order: {
        orderId: "5db2082f-0121-4aac-bb60-3940c62cb42d",
        orderNumber: "A-USD",
        status: "new",
        serverTotalMinor: 12345,
        currency: "USD",
        serverReceivedAt: new Date("2026-07-14T12:00:00Z"),
        needsConfirmation: false,
      },
      replayed: false,
    });
    renderCart();

    await user.click(screen.getByRole("button", { name: "Добавить" }));
    await user.type(screen.getByLabelText("Имя"), "Анна");
    await user.type(screen.getByLabelText("Телефон"), "+79990000000");
    await user.click(screen.getByRole("button", { name: "Оформить заказ" }));

    await screen.findByRole("heading", { name: "Заказ № A-USD" });
    const total = screen.getByText(
      (_, element) =>
        element?.tagName === "STRONG" &&
        element.textContent === formatMoney(12345, "USD", "ru-RU"),
    );
    expect(total.textContent).toBe(formatMoney(12345, "USD", "ru-RU"));
    expect(total.textContent).not.toBe(formatMoney(12345, "RUB", "ru-RU"));
  });

  it("has no detectable accessibility violations with the cart form visible", async () => {
    const user = userEvent.setup();
    const { container } = renderCart();
    await user.click(screen.getByRole("button", { name: "Добавить" }));
    await waitFor(() => expect(screen.getByText("Предварительно")).toBeInTheDocument());

    const result = await axe.run(container);
    expect(result.violations).toEqual([]);
  });

  it("locks mutation controls around the immutable submission snapshot", async () => {
    const user = userEvent.setup();
    let resolveSubmission: ((value: unknown) => void) | undefined;
    submitMock.mockReturnValue(new Promise((resolve) => { resolveSubmission = resolve; }));
    renderCart();
    await user.click(screen.getByRole("button", { name: "Добавить" }));
    await user.type(screen.getByLabelText("Имя"), "Анна");
    await user.type(screen.getByLabelText("Телефон"), "+79990000000");
    await user.click(screen.getByRole("button", { name: "Оформить заказ" }));

    expect(useCartStore.getState().locked).toBe(true);
    expect(screen.getByRole("button", { name: "Добавить" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Увеличить: Салат" })).toBeDisabled();
    resolveSubmission?.({
      order: {
        orderId: crypto.randomUUID(), orderNumber: "A-43", status: "new",
        serverTotalMinor: 49000, currency: "RUB", serverReceivedAt: new Date(), needsConfirmation: false,
      },
      replayed: false,
    });
    await screen.findByRole("heading", { name: "Заказ № A-43" });
    expect(useCartStore.getState().locked).toBe(false);
  });

  it("enters, traps and restores modal focus and closes with Escape", async () => {
    const user = userEvent.setup();
    renderCart();
    await user.click(screen.getByRole("button", { name: "Добавить" }));
    const trigger = await screen.findByRole("button", { name: /Корзина: 1/u });
    trigger.focus();
    await user.click(trigger);
    const dialog = screen.getByRole("dialog", { name: "Корзина" });
    const close = screen.getByRole("button", { name: "Закрыть корзину" });
    expect(close).toHaveFocus();

    await user.tab({ shift: true });
    expect(dialog.contains(document.activeElement)).toBe(true);
    await user.keyboard("{Escape}");
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    await waitFor(() => expect(trigger).toHaveFocus());
  });

  it("keeps Tab inside the modal while the checkout fieldset is disabled for submission", async () => {
    const user = userEvent.setup();
    let resolveSubmission: ((value: unknown) => void) | undefined;
    submitMock.mockReturnValue(new Promise((resolve) => { resolveSubmission = resolve; }));
    renderCart();
    await user.click(screen.getByRole("button", { name: "Добавить" }));
    await user.click(await screen.findByRole("button", { name: /Корзина: 1/u }));

    const dialog = screen.getByRole("dialog", { name: "Корзина" });
    const close = screen.getByRole("button", { name: "Закрыть корзину" });
    await user.type(screen.getByLabelText("Имя"), "Анна");
    await user.type(screen.getByLabelText("Телефон"), "+79990000000");
    await user.click(screen.getByRole("button", { name: "Оформить заказ" }));

    await waitFor(() => expect(useCartStore.getState().locked).toBe(true));
    expect(screen.getByLabelText("Имя")).toBeDisabled();
    close.focus();
    await user.tab();
    expect(close).toHaveFocus();
    await user.tab({ shift: true });
    expect(close).toHaveFocus();
    expect(dialog.contains(document.activeElement)).toBe(true);

    resolveSubmission?.({
      order: {
        orderId: crypto.randomUUID(), orderNumber: "A-44", status: "new",
        serverTotalMinor: 49000, currency: "RUB", serverReceivedAt: new Date(), needsConfirmation: false,
      },
      replayed: false,
    });
    await screen.findByRole("heading", { name: "Заказ № A-44" });
  });

  it("associates live validation errors with invalid fields", async () => {
    const user = userEvent.setup();
    renderCart();
    await user.click(screen.getByRole("button", { name: "Добавить" }));
    await user.click(screen.getByRole("button", { name: "Оформить заказ" }));

    const name = screen.getByLabelText("Имя");
    const phone = screen.getByLabelText("Телефон");
    expect(name).toHaveAttribute("aria-invalid", "true");
    expect(phone).toHaveAttribute("aria-invalid", "true");
    expect(name.getAttribute("aria-describedby")).toBeTruthy();
    expect(document.getElementById(name.getAttribute("aria-describedby")!)).toHaveAttribute("aria-live", "polite");
  });
});
