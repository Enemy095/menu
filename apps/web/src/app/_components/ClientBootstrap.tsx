"use client";

import { useEffect } from "react";

const HYDRATED_EVENT = "e-menu:hydrated";

export function ClientBootstrap() {
  useEffect(() => {
    const root = document.documentElement;
    const viewport = window.visualViewport;
    const updateViewportGeometry = () => {
      const height = viewport?.height ?? window.innerHeight;
      root.style.setProperty("--app-viewport-height", `${Math.round(height)}px`);
      root.style.setProperty(
        "--app-viewport-offset-top",
        `${Math.round(viewport?.offsetTop ?? 0)}px`,
      );
      root.style.setProperty(
        "--app-viewport-offset-left",
        `${Math.round(viewport?.offsetLeft ?? 0)}px`,
      );
    };

    updateViewportGeometry();
    viewport?.addEventListener("resize", updateViewportGeometry);
    viewport?.addEventListener("scroll", updateViewportGeometry);
    window.addEventListener("orientationchange", updateViewportGeometry);
    document.documentElement.dataset.appHydrated = "true";
    window.dispatchEvent(new CustomEvent(HYDRATED_EVENT));

    return () => {
      viewport?.removeEventListener("resize", updateViewportGeometry);
      viewport?.removeEventListener("scroll", updateViewportGeometry);
      window.removeEventListener("orientationchange", updateViewportGeometry);
    };
  }, []);

  return null;
}

export const clientBootstrapEvent = HYDRATED_EVENT;
