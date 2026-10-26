"use client";

import { useEffect, useRef, useState } from "react";

const sections = [{ id: "menu", label: "Меню" }, { id: "contacts", label: "Контакты" }, { id: "work-schedule", label: "График работы" }];

export function SiteNavigation() {
  const [active, setActive] = useState("menu");
  const requested = useRef<string | null>(null);
  useEffect(() => {
    let frame = 0;
    function update() {
      frame = 0;
      if (requested.current) return;
      const offset = (document.querySelector(".site-header")?.getBoundingClientRect().bottom ?? 0) + 36;
      let next = "menu";
      for (const section of sections) {
        if ((document.getElementById(section.id)?.getBoundingClientRect().top ?? Infinity) <= offset) next = section.id;
      }
      if (window.scrollY > 0 && window.innerHeight + window.scrollY >= document.documentElement.scrollHeight - 2)
        next = "work-schedule";
      setActive(next);
    }
    function schedule() { if (!frame) frame = window.requestAnimationFrame(update); }
    function hashChanged() {
      const id = window.location.hash.slice(1);
      if (sections.some(section => section.id === id)) { requested.current = id; setActive(id); }
      else { requested.current = null; schedule(); }
    }
    function manualScroll() { requested.current = null; schedule(); }
    function keyboard(event: KeyboardEvent) {
      if (["ArrowUp", "ArrowDown", "PageUp", "PageDown", "Home", "End", " "].includes(event.key)) manualScroll();
    }
    hashChanged();
    window.addEventListener("scroll", schedule, { passive: true });
    window.addEventListener("resize", schedule);
    window.addEventListener("hashchange", hashChanged);
    window.addEventListener("wheel", manualScroll, { passive: true });
    window.addEventListener("touchmove", manualScroll, { passive: true });
    window.addEventListener("keydown", keyboard);
    return () => {
      window.cancelAnimationFrame(frame);
      window.removeEventListener("scroll", schedule); window.removeEventListener("resize", schedule);
      window.removeEventListener("hashchange", hashChanged); window.removeEventListener("wheel", manualScroll);
      window.removeEventListener("touchmove", manualScroll); window.removeEventListener("keydown", keyboard);
    };
  }, []);
  return <nav className="site-header__nav" aria-label="Основная навигация">
    {sections.map(section => <a key={section.id} href={`#${section.id}`} aria-current={active === section.id ? "location" : undefined}
      onClick={() => { requested.current = section.id; setActive(section.id); }}>{section.label}</a>)}
  </nav>;
}
