"use client";

import { useState } from "react";
import { useBusinessAvailability } from "@/entities/restaurant/model/business-availability";

const weekdays = ["Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс"];
const dayLabel = new Intl.DateTimeFormat("ru-RU", { day: "numeric", month: "long", year: "numeric", timeZone: "UTC" });
const monthLabel = new Intl.DateTimeFormat("ru-RU", { month: "long", year: "numeric", timeZone: "UTC" });

export function WorkSchedule({ displayText }: { displayText: string }) {
  const { availability, message } = useBusinessAvailability();
  const [offset, setOffset] = useState(0);
  const today = availability ? new Date(`${availability.businessDate}T12:00:00Z`) : null;
  const month = today ? new Date(Date.UTC(today.getUTCFullYear(), today.getUTCMonth() + offset, 1, 12)) : null;
  const start = month ? (month.getUTCDay() + 6) % 7 : 0;
  const count = month ? new Date(Date.UTC(month.getUTCFullYear(), month.getUTCMonth() + 1, 0)).getUTCDate() : 0;
  const dates = new Set(availability?.closedDates ?? []);
  const horizon = today ? new Date(today.getTime() + 365 * 86400000) : null;

  return (
    <section className="work-schedule" id="work-schedule" aria-labelledby="work-schedule-title">
      <div className="work-schedule__description">
        <p className="eyebrow">График работы</p>
        <h2 id="work-schedule-title">Планируйте ваш визит</h2>
        <p>{displayText}</p>
        <p>Выходные отмечены красным. Проверьте выбранную дату перед заказом.</p>
      </div>
      {!availability || !month || !today || !horizon ? <p role="status">{message || "Загружаем календарь…"}</p> : (
        <div className="work-calendar" aria-label="Календарь выходных">
          <div className="work-calendar__heading">
            <button type="button" aria-label="Предыдущий месяц" disabled={offset === 0} onClick={() => setOffset(value => Math.max(0, value - 1))}>‹</button>
            <h3 aria-live="polite">{monthLabel.format(month)}</h3>
            <button type="button" aria-label="Следующий месяц" disabled={offset >= 12} onClick={() => setOffset(value => Math.min(12, value + 1))}>›</button>
          </div>
          <div className="work-calendar__grid" role="list" aria-label={monthLabel.format(month)}>
            {weekdays.map(day => <span className="work-calendar__weekday" aria-hidden="true" key={day}>{day}</span>)}
            {Array.from({ length: start }, (_, index) => <span aria-hidden="true" key={`blank-${index}`} />)}
            {Array.from({ length: count }, (_, index) => {
              const day = index + 1;
              const date = new Date(Date.UTC(month.getUTCFullYear(), month.getUTCMonth(), day, 12));
              const iso = date.toISOString().slice(0, 10);
              const known = date >= today && date <= horizon;
              const closed = known && (dates.has(iso) || availability.weeklyClosedDays.includes((date.getUTCDay() + 6) % 7 + 1));
              const current = iso === availability.businessDate;
              const status = !known ? "нет актуальных данных" : closed ? "выходной" : "рабочий день";
              return <span role="listitem" key={iso} className={`work-calendar__day${closed ? " work-calendar__day--closed" : ""}${!known ? " work-calendar__day--unknown" : ""}`} aria-current={current ? "date" : undefined} aria-label={`${dayLabel.format(date)} — ${status}`} title={status}>{day}</span>;
            })}
          </div>
          <div className="work-calendar__legend"><span />Выходной <small>Сегодня — в золотой рамке</small></div>
        </div>
      )}
    </section>
  );
}
