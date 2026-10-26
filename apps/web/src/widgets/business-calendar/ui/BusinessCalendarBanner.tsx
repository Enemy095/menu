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
  if (!availability?.closedToday) return null;
  return (
    <aside className="business-calendar" aria-label="Сегодня выходной" aria-live="polite">
      <strong>Сегодня выходной</strong>
      <p>Ждём вас в следующий рабочий день.</p>
    </aside>
  );
}
