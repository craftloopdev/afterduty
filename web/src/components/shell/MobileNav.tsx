"use client";

import Link from "next/link";
import { Icon, type IconName } from "@/components/ui/Icon";
import styles from "./MobileNav.module.css";

// Five even tabs. The "Add evidence" action lives in the top bar, so the bottom
// bar stays symmetric and the one prominent element is always the CURRENT page.
const TABS: { href: string; label: string; icon: IconName }[] = [
  { href: "/", label: "Home", icon: "home" },
  { href: "/conditions", label: "Conditions", icon: "conditions" },
  { href: "/steps", label: "Steps", icon: "target" },
  { href: "/documents", label: "Docs", icon: "file" },
  { href: "/ask", label: "Ask AI", icon: "chat" },
];

export function MobileNav({ active }: { active: string }) {
  return (
    <nav className={styles.nav} aria-label="Primary">
      {TABS.map((it) => {
        const on = active === it.href;
        return (
          <Link
            key={it.href}
            href={it.href}
            className={styles.tab}
            data-active={on ? "1" : "0"}
            aria-current={on ? "page" : undefined}
          >
            <span className={styles.pill}>
              <Icon name={it.icon} size={on ? 24 : 22} stroke={on ? 2.4 : 2} />
            </span>
            <span className={styles.label}>{it.label}</span>
          </Link>
        );
      })}
    </nav>
  );
}
