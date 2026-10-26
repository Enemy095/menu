import { render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { BusinessCalendarBanner } from "./BusinessCalendarBanner";

afterEach(() => vi.unstubAllGlobals());
describe("BusinessCalendarBanner", () => {
  it("uses the server's business date and announces weekly and dated days off", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(Response.json({
      businessDate: "2026-10-28", timezone: "Europe/Moscow", closedToday: true,
      weeklyClosedDays: [3], closedDates: ["2026-11-04"],
    })));
    render(<BusinessCalendarBanner />);
    expect(await screen.findByText("Сегодня выходной")).toBeInTheDocument();
    expect(screen.getByText(/Еженедельно выходной: среда/)).toBeInTheDocument();
    expect(screen.getByText(/4 ноября/)).toBeInTheDocument();
  });
  it("does not invent a closed-day announcement from an invalid external response", async () => {
    const fetch = vi.fn().mockResolvedValue(Response.json({ closedToday: true }));
    vi.stubGlobal("fetch", fetch);
    render(<BusinessCalendarBanner />);
    await waitFor(() => expect(fetch).toHaveBeenCalled());
    expect(screen.queryByText("Сегодня выходной")).not.toBeInTheDocument();
  });
});
