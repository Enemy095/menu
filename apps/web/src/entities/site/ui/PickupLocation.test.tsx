import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { PickupLocation } from "./PickupLocation";

describe("PickupLocation", () => {
  it("uses the configured address and verified coordinates for the preview and external link", () => {
    render(<PickupLocation profile={{address: "Хасавюрт, 20-я улица, 12", location:{latitude:43.225478,longitude:46.566266,label:"Самовывоз"}}} />);
    const link = screen.getByRole("link", {name:/Открыть адрес самовывоза/});
    const url = new URL(link.getAttribute("href")!);
    expect(url.hostname).toBe("yandex.ru");
    expect(url.searchParams.get("pt")).toBe("46.566266,43.225478");
    expect(link).toHaveAttribute("target", "_blank");
    expect(screen.getByTitle(/Карта:/)).toHaveAttribute("src", expect.stringContaining("map-widget/v1/"));
  });
  it("does not invent a pickup point when location is missing", () => {
    const { container } = render(<PickupLocation profile={{address:"Адрес"}} />);
    expect(container).toBeEmptyDOMElement();
  });
});
