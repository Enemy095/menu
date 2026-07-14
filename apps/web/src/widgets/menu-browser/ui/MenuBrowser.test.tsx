import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";

import { menuFixture } from "@/test/fixtures";

import { MenuBrowser } from "./MenuBrowser";

describe("MenuBrowser", () => {
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

    expect(screen.getByText("Сезонный салат").closest("li")).not.toHaveAttribute("hidden");
    await waitFor(() => expect(screen.getByText("Овощи на гриле").closest("li")).toHaveAttribute("hidden"));
    expect(screen.getByRole("button", { name: "Закуски" })).toHaveAttribute("aria-pressed", "true");
  });

  it("renders an explicit empty state without inventing menu data", () => {
    render(
      <MenuBrowser title="Меню" categories={[]} items={[]} locale="ru-RU" currency="RUB" />,
    );

    expect(screen.getByRole("status")).toHaveTextContent("Меню пока не опубликовано");
  });
});
