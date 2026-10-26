"use client";

import { useBusinessAvailability } from "@/entities/restaurant/model/business-availability";

export function BusinessCalendarBanner() {
  const { closedToday, message, reopening } = useBusinessAvailability();
  if (!closedToday) return null;
  return (
    <aside className="business-calendar" aria-label="Сегодня выходной" aria-live="polite">
      <strong>Сегодня выходной</strong>
      <p>{message}</p>
      {reopening ? <p>{reopening}</p> : null}
    </aside>
  );
}
