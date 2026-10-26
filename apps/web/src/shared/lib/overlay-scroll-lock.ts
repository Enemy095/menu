"use client";

import { useEffect } from "react";

type BodySnapshot = {
  position: string;
  top: string;
  right: string;
  bottom: string;
  left: string;
  width: string;
  overflow: string;
};

let lockCount = 0;
let lockedScrollY = 0;
let snapshot: BodySnapshot | null = null;

function lockPageScroll(): () => void {
  lockCount += 1;
  if (lockCount > 1) return unlockPageScroll;

  lockedScrollY = window.scrollY;
  snapshot = {
    position: document.body.style.position,
    top: document.body.style.top,
    right: document.body.style.right,
    bottom: document.body.style.bottom,
    left: document.body.style.left,
    width: document.body.style.width,
    overflow: document.body.style.overflow,
  };

  document.documentElement.dataset.overlayOpen = "true";
  Object.assign(document.body.style, {
    position: "fixed",
    top: `-${lockedScrollY}px`,
    right: "0",
    bottom: "auto",
    left: "0",
    width: "100%",
    overflow: "hidden",
  });
  return unlockPageScroll;
}

function unlockPageScroll(): void {
  lockCount = Math.max(0, lockCount - 1);
  if (lockCount || !snapshot) return;

  Object.assign(document.body.style, snapshot);
  snapshot = null;
  delete document.documentElement.dataset.overlayOpen;
  window.scrollTo(0, lockedScrollY);
}

export function useOverlayScrollLock(active: boolean): void {
  useEffect(() => {
    if (!active) return;
    return lockPageScroll();
  }, [active]);
}
