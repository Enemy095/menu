import { act, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it } from "vitest";
import { SiteNavigation } from "./SiteNavigation";

afterEach(() => window.history.replaceState(null, "", "/"));
describe("SiteNavigation", () => {
  it("moves the active state from menu to each selected section", async () => {
    const user = userEvent.setup();
    render(<SiteNavigation />);
    const menu = screen.getByRole("link", {name:"Меню"});
    const contacts = screen.getByRole("link", {name:"Контакты"});
    const schedule = screen.getByRole("link", {name:"График работы"});
    expect(menu).toHaveAttribute("aria-current", "location");
    await user.click(contacts);
    expect(contacts).toHaveAttribute("aria-current", "location");
    expect(menu).not.toHaveAttribute("aria-current");
    await user.click(schedule);
    expect(schedule).toHaveAttribute("aria-current", "location");
    expect(contacts).not.toHaveAttribute("aria-current");
    expect(document.querySelectorAll('[aria-current="location"]')).toHaveLength(1);
  });
  it("follows browser hash navigation", async () => {
    render(<SiteNavigation />);
    await act(async () => { window.history.replaceState(null, "", "#contacts"); window.dispatchEvent(new HashChangeEvent("hashchange")); });
    expect(screen.getByRole("link", {name:"Контакты"})).toHaveAttribute("aria-current", "location");
  });
});
