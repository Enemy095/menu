import { act, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { BusinessAvailabilityProvider } from "@/entities/restaurant/model/business-availability";
import { WorkSchedule } from "./WorkSchedule";

afterEach(() => vi.unstubAllGlobals());
describe("WorkSchedule", () => {
  it("marks explicit and recurring closures and refreshes when the owner's changes arrive", async () => {
    let dates = ["2026-10-28"];
    vi.stubGlobal("fetch", vi.fn().mockImplementation(async () => Response.json({
      businessDate: "2026-10-26", timezone: "Europe/Moscow", closedToday: false,
      weeklyClosedDays: [4], closedDates: dates,
    })));
    render(<BusinessAvailabilityProvider><WorkSchedule displayText="10:00–23:00" /></BusinessAvailabilityProvider>);
    expect(await screen.findByLabelText(/28 октября 2026.*выходной/)).toHaveClass("work-calendar__day--closed");
    expect(screen.getByLabelText(/29 октября 2026.*выходной/)).toHaveClass("work-calendar__day--closed");
    expect(screen.getByLabelText(/30 октября 2026.*рабочий день/)).not.toHaveClass("work-calendar__day--closed");
    dates = [];
    await act(async () => { window.dispatchEvent(new Event("focus")); });
    expect(await screen.findByLabelText(/28 октября 2026.*рабочий день/)).not.toHaveClass("work-calendar__day--closed");
    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: "Следующий месяц" }));
    expect(screen.getByRole("heading", { name: /ноябрь 2026/ })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Предыдущий месяц" }));
    expect(screen.getByRole("button", { name: "Предыдущий месяц" })).toBeDisabled();
  });
  it("does not invent a calendar before valid availability is loaded", async () => {
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new Error("unavailable")));
    await act(async () => { render(<BusinessAvailabilityProvider><WorkSchedule displayText="10:00–23:00" /></BusinessAvailabilityProvider>); });
    expect(screen.queryByLabelText("Календарь выходных")).not.toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent("Не удалось проверить");
  });
});
