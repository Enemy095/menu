import axe from "axe-core";
import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { configurationFixture, menuFixture } from "@/test/fixtures";

import { PublicMenuPage } from "./PublicMenuPage";

describe("PublicMenuPage", () => {
  it("renders configuration and menu data at the SSR composition boundary", () => {
    render(<PublicMenuPage configuration={configurationFixture()} menu={menuFixture()} />);

    expect(
      screen.getByRole("heading", { level: 1, name: /Вкусная еда.*Быстрая доставка/u }),
    ).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Смотреть меню" })).toHaveAttribute("href", "#menu");
    expect(screen.queryByText(/Доставка от 1000/u)).not.toBeInTheDocument();
    expect(screen.getByRole("heading", { level: 2, name: "Популярное" })).toBeInTheDocument();
    expect(screen.getAllByText("Ежедневно 10:00–22:00")).toHaveLength(2);
    expect(screen.getByRole("link", { name: "График работы" })).toHaveAttribute("href", "#work-schedule");
    expect(screen.getByText(/490,00\s₽/u)).toBeInTheDocument();
  });

  it("has no detectable accessibility violations in the initial view", async () => {
    const { container } = render(
      <PublicMenuPage configuration={configurationFixture()} menu={menuFixture()} />,
    );

    const result = await axe.run(container);
    expect(result.violations).toEqual([]);
  });
});
