"use client";

import { useEffect, type RefObject } from "react";

// Everything a modal surface can reasonably contain. Kept simple on purpose —
// overlays here are small, so we don't filter by visibility (which jsdom can't
// compute anyway).
const FOCUSABLE =
  'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

/**
 * Modal a11y trio (P1-25), used by Modal (and any future overlay surface):
 *  1. Focus moves into the surface on open and is TRAPPED there (Tab/Shift+Tab
 *     cycle; focus can't wander into the page behind the scrim).
 *  2. Escape closes.
 *  3. On close, focus RESTORES to whatever opened the dialog, and the body
 *     scroll lock is released.
 */
export function useFocusTrap(
  ref: RefObject<HTMLElement | null>,
  open: boolean,
  onClose: () => void,
) {
  useEffect(() => {
    if (!open) return;
    const opener = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    ref.current?.focus();

    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        onClose();
        return;
      }
      if (e.key !== "Tab") return;
      const surface = ref.current;
      if (!surface) return;
      const items = Array.from(surface.querySelectorAll<HTMLElement>(FOCUSABLE));
      if (items.length === 0) {
        e.preventDefault();
        surface.focus();
        return;
      }
      const first = items[0];
      const last = items[items.length - 1];
      const active = document.activeElement;
      const inside = active instanceof HTMLElement && surface.contains(active);
      if (e.shiftKey) {
        if (!inside || active === first || active === surface) {
          e.preventDefault();
          last.focus();
        }
      } else if (!inside || active === last) {
        e.preventDefault();
        first.focus();
      }
    };
    document.addEventListener("keydown", onKey);

    const prevOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";

    return () => {
      document.removeEventListener("keydown", onKey);
      document.body.style.overflow = prevOverflow;
      opener?.focus();
    };
  }, [ref, open, onClose]);
}
