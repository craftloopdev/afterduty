"use client";

import { useId, useRef } from "react";
import { Icon } from "./Icon";
import { useFocusTrap } from "./useFocusTrap";
import styles from "./Modal.module.css";

interface ModalProps {
  open: boolean;
  onClose: () => void;
  title?: string;
  /**
   * Accessible name for title-less modals (P1-25 — a dialog must never be
   * unnamed). When `title` is set the header labels the dialog instead.
   */
  ariaLabel?: string;
  size?: "sm" | "md";
  children: React.ReactNode;
}

export function Modal({ open, onClose, title, ariaLabel, size = "md", children }: ModalProps) {
  const cardRef = useRef<HTMLDivElement>(null);
  const titleId = useId();

  // Focus trap + restore + Escape + body scroll lock (P1-25).
  useFocusTrap(cardRef, open, onClose);

  if (!open) return null;

  const cls = [styles.card, size === "sm" && styles.sm].filter(Boolean).join(" ");

  return (
    <div className={styles.scrim} onClick={onClose}>
      <div
        ref={cardRef}
        className={cls}
        role="dialog"
        aria-modal="true"
        aria-labelledby={title ? titleId : undefined}
        aria-label={title ? undefined : ariaLabel}
        tabIndex={-1}
        onClick={(e) => e.stopPropagation()}
      >
        {title && (
          <div className={styles.head}>
            <h3 id={titleId} className={styles.title}>
              {title}
            </h3>
            <button type="button" className={styles.close} aria-label="Close" onClick={onClose}>
              <Icon name="close" size={20} />
            </button>
          </div>
        )}
        {children}
      </div>
    </div>
  );
}
