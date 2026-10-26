"use client";

import { useEffect, useState } from "react";
import { z } from "zod";

const availabilitySchema = z.object({
  businessDate: z.iso.date(),
  timezone: z.string().min(1),
  closedToday: z.boolean(),
  weeklyClosedDays: z.array(z.number().int().min(1).max(7)).max(7),
  closedDates: z.array(z.iso.date()).max(366),
}).strict();
type Availability = z.infer<typeof availabilitySchema>;
const weekdays = ["понедельник", "вторник", "среда", "четверг", "пятница", "суббота", "воскресенье"];
const formatDate = (date: string) => new Intl.DateTimeFormat("ru-RU", {
  day: "numeric", month: "long", weekday: "short", timeZone: "UTC",
}).format(new Date(`${date}T12:00:00Z`));

export function BusinessCalendarBanner() {
  const [availability, setAvailability] = useState<Availability | null>(null);
  useEffect(() => {
    let disposed = false;
    let controller: AbortController | undefined;
    async function refresh() {
      controller?.abort();
      controller = new AbortController();
      try {
        const response = await fetch("/api/v1/public/availability", { cache: "no-store", signal: controller.signal });
        if (!response.ok) return;
        const parsed = availabilitySchema.safeParse(await response.json());
        if (parsed.success && !disposed) setAvailability(parsed.data);
      } catch { /* Keep the last confirmed information during transient failures. */ }
    }
    void refresh();
    const timer = window.setInterval(() => void refresh(), 30_000);
    const onFocus = () => void refresh();
    window.addEventListener("focus", onFocus);
    return () => { disposed = true; controller?.abort(); window.clearInterval(timer); window.removeEventListener("focus", onFocus); };
  }, []);
  if (!availability) return null;
  const future = availability.closedDates.filter(date => date >= availability.businessDate);
  if (!availability.closedToday && !availability.weeklyClosedDays.length && !future.length) return null;
  return (
    <aside className="business-calendar shell" aria-label="Выходные дни" aria-live="polite">
      {availability.closedToday ? <strong>Сегодня выходной</strong> : <strong>Обратите внимание на выходные дни</strong>}
      {availability.weeklyClosedDays.length ? <p>Еженедельно выходной: {availability.weeklyClosedDays.map(day => weekdays[day - 1]).join(", ")}.</p> : null}
      {future.length ? <p>Выходные даты: {future.map(formatDate).join("; ")}.</p> : null}
    </aside>
  );
}
