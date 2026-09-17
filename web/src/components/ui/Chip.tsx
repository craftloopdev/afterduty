"use client";

import styles from "./Chip.module.css";

interface ChipProps {
  active?: boolean;
  count?: number;
  onClick?: () => void;
  children: React.ReactNode;
}

export function Chip({ active, count, onClick, children }: ChipProps) {
  const cls = [styles.chip, active && styles.active].filter(Boolean).join(" ");
  return (
    <button type="button" className={cls} data-active={active || undefined} onClick={onClick}>
      {children}
      {count != null && (
        <span className={styles.count} data-active={active || undefined}>
          {count}
        </span>
      )}
    </button>
  );
}
