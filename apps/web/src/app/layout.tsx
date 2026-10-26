import type { Metadata, Viewport } from "next";
import type { ReactNode } from "react";

import { ClientBootstrap } from "./_components/ClientBootstrap";
import "./globals.css";
import "./interaction-contract.css";

export const metadata: Metadata = {
  title: "Электронное меню",
  robots: { index: false, follow: false },
};

export const viewport: Viewport = {
  width: "device-width",
  initialScale: 1,
  viewportFit: "cover",
  themeColor: "#080808",
};

const bootstrapWatchdog = `
(() => {
  const eventName = "e-menu:hydrated";
  const startedAt = Date.now();
  const params = new URLSearchParams(window.location.search);
  const debug = params.get("debug") === "1";
  const status = () => document.getElementById("app-bootstrap-status");
  const diagnostics = () => document.getElementById("app-bootstrap-diagnostics");
  let hydrated = false;

  function writeDiagnostics(state, detail) {
    const target = diagnostics();
    if (!target) return;
    target.hidden = !debug;
    target.textContent = [
      "client=" + state,
      "url=" + window.location.href,
      "userAgent=" + navigator.userAgent,
      "online=" + navigator.onLine,
      "elapsedMs=" + (Date.now() - startedAt),
      detail ? "detail=" + detail : ""
    ].filter(Boolean).join("\\n");
  }

  function markHydrated() {
    hydrated = true;
    document.documentElement.dataset.appHydrated = "true";
    const target = status();
    if (target) {
      target.hidden = !debug;
      target.classList.toggle("bootstrap-status--debug", debug);
    }
    writeDiagnostics("hydrated");
  }

  window.addEventListener(eventName, markHydrated, { once: true });
  window.addEventListener("error", (event) => {
    document.documentElement.dataset.appRuntimeError = "true";
    writeDiagnostics("error", event.message || "resource-load-error");
  }, true);
  window.addEventListener("unhandledrejection", (event) => {
    document.documentElement.dataset.appRuntimeError = "true";
    writeDiagnostics("rejection", String(event.reason || "unknown"));
  });
  document.addEventListener("click", (event) => {
    const reload = event.target instanceof Element && event.target.closest("[data-bootstrap-reload]");
    if (reload) window.location.reload();
  });

  window.setTimeout(() => {
    if (hydrated || document.documentElement.dataset.appHydrated === "true") return;
    document.documentElement.dataset.appHydrationTimeout = "true";
    const target = status();
    if (target) target.hidden = false;
    writeDiagnostics("timeout");
  }, 6000);

  writeDiagnostics("booting");
})();
`;

export default function RootLayout({ children }: Readonly<{ children: ReactNode }>) {
  return (
    <html lang="ru-RU">
      <body>
        <script dangerouslySetInnerHTML={{ __html: bootstrapWatchdog }} />
        <ClientBootstrap />
        <aside id="app-bootstrap-status" className="bootstrap-status" role="alert" hidden>
          <strong>Интерактивная часть сайта не загрузилась.</strong>
          <span>Проверьте соединение и обновите страницу.</span>
          <button type="button" data-bootstrap-reload>Обновить</button>
          <pre id="app-bootstrap-diagnostics" hidden />
        </aside>
        <a className="skip-link" href="#main-content">
          Перейти к содержимому
        </a>
        <div id="main-content">{children}</div>
      </body>
    </html>
  );
}
