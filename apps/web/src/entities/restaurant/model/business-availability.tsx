"use client";

import { createContext, useContext, useEffect, useState, type ReactNode } from "react";
import { z } from "zod";

const schema = z.object({
  businessDate: z.iso.date(), timezone: z.string().min(1), closedToday: z.boolean(),
  weeklyClosedDays: z.array(z.number().int().min(1).max(7)).max(7),
  closedDates: z.array(z.iso.date()).max(366),
  nextOpenDate: z.iso.date().nullable().optional(),
}).strict();
type Availability = z.infer<typeof schema>;
export const CLOSED_MESSAGE = "Сегодня выходной. К сожалению, оформить заказ нельзя.";
const Context = createContext({ closedToday: false, blocked: false, message: "", reopening: "", availability: null as Availability | null });
const workingDays = ["в воскресенье", "в понедельник", "во вторник", "в среду", "в четверг", "в пятницу", "в субботу"];

export function BusinessAvailabilityProvider({ children }: { children: ReactNode }) {
  const [availability, setAvailability] = useState<z.infer<typeof schema> | null>(null);
  const [failed, setFailed] = useState(false);
  useEffect(() => {
    let disposed = false;
    let controller: AbortController | undefined;
    async function refresh() {
      controller?.abort();
      const request = new AbortController();
      controller = request;
      try {
        const response = await fetch("/api/v1/public/availability", { cache: "no-store", signal: request.signal });
        if (!response.ok) throw new Error("Availability unavailable");
        const data = schema.parse(await response.json());
        if (!disposed) { setAvailability(data); setFailed(false); }
      } catch {
        if (!disposed && !request.signal.aborted) setFailed(true);
      }
    }
    void refresh();
    const timer = window.setInterval(() => void refresh(), 30_000);
    const onFocus = () => void refresh();
    window.addEventListener("focus", onFocus);
    return () => { disposed = true; controller?.abort(); window.clearInterval(timer); window.removeEventListener("focus", onFocus); };
  }, []);
  const closedToday = availability?.closedToday ?? false;
  const message = closedToday ? CLOSED_MESSAGE : !availability
    ? failed ? "Не удалось проверить доступность заказов. Обновите страницу." : "Проверяем возможность заказа…"
    : "";
  const nextDate = availability?.nextOpenDate ? new Date(`${availability.nextOpenDate}T12:00:00Z`) : null;
  const reopening = closedToday && nextDate ? `Снова работаем ${workingDays[nextDate.getUTCDay()]}, ${new Intl.DateTimeFormat("ru-RU", {
    day: "numeric", month: "long", timeZone: "UTC",
  }).format(nextDate)}. Возвращайтесь к нам в этот день.` : "";
  return <Context value={{ closedToday, blocked: !availability || closedToday, message, reopening, availability }}>{children}</Context>;
}

export function useBusinessAvailability() { return useContext(Context); }
