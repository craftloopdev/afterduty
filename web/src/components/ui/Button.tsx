"use client";

import type { ButtonHTMLAttributes } from "react";
import { Icon, type IconName } from "./Icon";
import styles from "./Button.module.css";

type Variant = "primary" | "ghost";
type Size = "sm" | "md" | "lg";

interface ButtonProps extends Omit<ButtonHTMLAttributes<HTMLButtonElement>, "children"> {
  variant?: Variant;
  size?: Size;
  icon?: IconName;
  full?: boolean;
  loading?: boolean;
  children: React.ReactNode;
}

const ICON_SIZE: Record<Size, number> = { sm: 16, md: 18, lg: 20 };

export function Button({
  variant = "primary",
  size = "md",
  icon,
  full,
  loading,
  children,
  className,
  disabled,
  ...rest
}: ButtonProps) {
  const cls = [styles.btn, styles[variant], styles[size], full && styles.full, className]
    .filter(Boolean)
    .join(" ");
  return (
    <button className={cls} disabled={disabled || loading} aria-busy={loading || undefined} {...rest}>
      {loading ? (
        <span className={styles.spinner} aria-hidden="true" />
      ) : (
        icon && <Icon name={icon} size={ICON_SIZE[size]} stroke={2.2} />
      )}
      {children}
    </button>
  );
}
