"use client";

import Link from "next/link";
import type { ComponentProps } from "react";
import { Icon, type IconName } from "./Icon";
import styles from "./Button.module.css";

type Variant = "primary" | "ghost";
type Size = "sm" | "md" | "lg";

interface ButtonLinkProps extends Omit<ComponentProps<typeof Link>, "children"> {
  variant?: Variant;
  size?: Size;
  icon?: IconName;
  full?: boolean;
  children: React.ReactNode;
}

const ICON_SIZE: Record<Size, number> = { sm: 16, md: 18, lg: 20 };

/**
 * next/link Link dressed as a Button — same module CSS, identical visuals.
 * Use for actions that navigate (e.g. modal CTAs) so they're honest links:
 * client-side nav, middle-click/new-tab, and real hrefs for assistive tech.
 */
export function ButtonLink({
  variant = "primary",
  size = "md",
  icon,
  full,
  children,
  className,
  ...rest
}: ButtonLinkProps) {
  const cls = [styles.btn, styles[variant], styles[size], full && styles.full, className]
    .filter(Boolean)
    .join(" ");
  return (
    <Link className={cls} {...rest}>
      {icon && <Icon name={icon} size={ICON_SIZE[size]} stroke={2.2} />}
      {children}
    </Link>
  );
}
