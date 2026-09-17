"use client";

import { useEffect, useState } from "react";

/**
 * Desktop ↔ mobile breakpoint. SSR-safe: defaults to desktop so the first paint
 * matches the CSS-first layout (the shell shows/hides nav via @media regardless;
 * this hook is for behavior that genuinely needs the boolean in JS).
 */
export function useBreakpoint(minWidth = 760): boolean {
  const [isWide, setIsWide] = useState(true);
  useEffect(() => {
    const mq = window.matchMedia(`(min-width: ${minWidth}px)`);
    const update = () => setIsWide(mq.matches);
    update();
    mq.addEventListener("change", update);
    return () => mq.removeEventListener("change", update);
  }, [minWidth]);
  return isWide;
}
