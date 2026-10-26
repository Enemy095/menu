import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { resetCartStoreForTests, useCartStore } from "@/entities/cart/model/cart-store";
import { CartProvider } from "@/features/cart/model/cart-context";
import { BusinessAvailabilityProvider } from "@/entities/restaurant/model/business-availability";
import { menuFixture } from "@/test/fixtures";

import { MenuBrowser } from "./MenuBrowser";

describe("MenuBrowser", () => {
  afterEach(() => vi.unstubAllGlobals());
  beforeEach(() => {
    window.localStorage.clear();
    resetCartStoreForTests();
  });

  it("filters items by the selected category", async () => {
    const user = userEvent.setup();
    const menu = menuFixture();
    render(
      <MenuBrowser
        title="Наше меню"
        categories={menu.categories}
        items={menu.items}
        locale="ru-RU"
        currency="RUB"
      />,
    );

    expect(screen.getByText("Сезонный салат")).toBeInTheDocument();
    expect(screen.getByText("Овощи на гриле")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Закуски" }));

    await waitFor(() => {
      expect(screen.getByText("Сезонный салат")).toBeInTheDocument();
      expect(screen.queryByText("Овощи на гриле")).not.toBeInTheDocument();
    });
    expect(screen.getByRole("button", { name: "Закуски" })).toHaveAttribute("aria-pressed", "true");
  });

  it("renders an explicit empty state without inventing menu data", () => {
    render(
      <MenuBrowser title="Меню" categories={[]} items={[]} locale="ru-RU" currency="RUB" />,
    );

    expect(screen.getByRole("status")).toHaveTextContent("Меню пока не опубликовано");
  });

  it("keeps legacy category order and uses the restaurant image as a missing-item fallback", () => {
    const menu = menuFixture();
    render(
      <MenuBrowser
        title="Меню"
        categories={menu.categories}
        items={[...menu.items].reverse()}
        locale="ru-RU"
        currency="RUB"
        fallbackImageUrl="https://cdn.example.test/assets/logo/fallback.png"
      />,
    );

    const cards = screen.getAllByRole("listitem");
    expect(cards[0]).toHaveTextContent("Сезонный салат");
    expect(cards[1]).toHaveTextContent("Овощи на гриле");
    expect(screen.getByRole("img", { name: "Овощи на гриле" })).toHaveAttribute(
      "src",
      "https://cdn.example.test/assets/logo/fallback.png",
    );
  });

  it("opens a dish dialog and adds the selected quantity atomically", async () => {
    const user = userEvent.setup();
    const menu = menuFixture();
    render(
      <CartProvider
        catalog={{ restaurantId: menu.restaurantId, menuVersion: menu.version, items: menu.items }}
        limits={{ maxItemQuantity: 10, maxTotalItems: 50 }}
      >
        <MenuBrowser
          title="Меню"
          categories={menu.categories}
          items={menu.items}
          locale="ru-RU"
          currency="RUB"
          orderingEnabled
        />
      </CartProvider>,
    );

    await user.click(screen.getByRole("button", { name: "Открыть: Сезонный салат" }));
    const dialog = screen.getByRole("dialog", { name: "Сезонный салат" });
    expect(within(dialog).getByText("490,00 ₽")).toBeInTheDocument();

    await user.click(within(dialog).getByRole("button", { name: "Увеличить количество" }));
    expect(within(dialog).getByText("980,00 ₽")).toBeInTheDocument();
    await user.click(within(dialog).getByRole("button", { name: /Добавить/u }));

    await waitFor(() => expect(screen.queryByRole("dialog", { name: "Сезонный салат" })).not.toBeInTheDocument());
    expect(useCartStore.getState().items).toEqual([
      expect.objectContaining({ menuItemId: "seasonal-salad", quantity: 2 }),
    ]);
  });
  it("allows dish browsing on a closed day but disables adding and quantity changes", async () => {
    const user = userEvent.setup();
    const menu = menuFixture();
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(Response.json({
      businessDate: "2026-10-28", timezone: "Europe/Moscow", closedToday: true,
      weeklyClosedDays: [3], closedDates: ["2026-10-29"], nextOpenDate: "2026-10-30",
    })));
    render(<BusinessAvailabilityProvider>
      <CartProvider catalog={{ restaurantId: menu.restaurantId, menuVersion: menu.version, items: menu.items }} limits={{ maxItemQuantity: 10, maxTotalItems: 50 }}>
        <MenuBrowser title="Меню" categories={menu.categories} items={menu.items} locale="ru-RU" currency="RUB" orderingEnabled />
      </CartProvider>
    </BusinessAvailabilityProvider>);
    await user.click(screen.getByRole("button", { name: "Открыть: Сезонный салат" }));
    const dialog = screen.getByRole("dialog", { name: "Сезонный салат" });
    expect(await within(dialog).findByText(/Сегодня выходной/)).toBeInTheDocument();
    expect(within(dialog).getByText(/в пятницу, 30 октября/)).toBeInTheDocument();
    expect(within(dialog).getByRole("button", { name: /Добавить/u })).toBeDisabled();
    expect(within(dialog).getByRole("button", { name: "Увеличить количество" })).toBeDisabled();
    expect(useCartStore.getState().items).toEqual([]);
  });
});
