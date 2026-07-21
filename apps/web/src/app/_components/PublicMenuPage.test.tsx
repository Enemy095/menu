import axe from "axe-core";
import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { configurationFixture, menuFixture } from "@/test/fixtures";

import { PublicMenuPage } from "./PublicMenuPage";

describe("PublicMenuPage", () => {
  it("renders configuration and menu data at the SSR composition boundary", () => {
    render(<PublicMenuPage configuration={configurationFixture()} menu={menuFixture()} />);

    expect(
      screen.getByRole("heading", { level: 1, name: "Вкусная едаБыстрая доставка" }),
    ).toBeInTheDocument();
    expect(screen.getByRole("heading", { level: 2, name: "Популярное" })).toBeInTheDocument();
    expect(screen.getByText("Ежедневно 10:00–22:00")).toBeInTheDocument();
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
