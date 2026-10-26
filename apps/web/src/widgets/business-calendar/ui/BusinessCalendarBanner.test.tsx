import { act, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { BusinessCalendarBanner } from "./BusinessCalendarBanner";

afterEach(() => vi.unstubAllGlobals());
describe("BusinessCalendarBanner", () => {
  it("announces today's closure without listing future dates or weekly days", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(Response.json({
      businessDate: "2026-10-28", timezone: "Europe/Moscow", closedToday: true,
      weeklyClosedDays: [3], closedDates: ["2026-11-04"],
    })));
    render(<BusinessCalendarBanner />);
    expect(await screen.findByText("Сегодня выходной")).toBeInTheDocument();
    expect(screen.queryByText(/Еженедельно/)).not.toBeInTheDocument();
    expect(screen.queryByText(/ноября/)).not.toBeInTheDocument();
  });
  it("stays hidden on an open day even with planned dates and weekly closures", async () => {
    const fetch = vi.fn().mockResolvedValue(Response.json({
      businessDate: "2026-10-26", timezone: "Europe/Moscow", closedToday: false,
      weeklyClosedDays: [3], closedDates: ["2026-10-28"],
    }));
    vi.stubGlobal("fetch", fetch);
    await act(async () => { render(<BusinessCalendarBanner />); });
    expect(fetch).toHaveBeenCalled();
    expect(screen.queryByRole("complementary")).not.toBeInTheDocument();
  });
  it("does not invent a closed-day announcement from an invalid external response", async () => {
    const fetch = vi.fn().mockResolvedValue(Response.json({ closedToday: true }));
    vi.stubGlobal("fetch", fetch);
    render(<BusinessCalendarBanner />);
    await waitFor(() => expect(fetch).toHaveBeenCalled());
    expect(screen.queryByText("Сегодня выходной")).not.toBeInTheDocument();
  });
});
