"use client";

import Link from "next/link";
import { Icon, type IconName } from "@/components/ui/Icon";
import type { SubscriptionState } from "@/lib/models/vm";
import styles from "./Sidebar.module.css";

const NAV: { href: string; label: string; icon: IconName }[] = [
  { href: "/", label: "Home", icon: "home" },
  { href: "/conditions", label: "Conditions", icon: "conditions" },
  { href: "/steps", label: "Next Steps", icon: "target" },
  { href: "/documents", label: "Documents", icon: "file" },
  { href: "/ask", label: "Ask AI", icon: "chat" },
];

interface SidebarProps {
  active: string;
  userName: string;
  branch?: string | null;
  subState: SubscriptionState;
  /** Viewer mode (P0-8): a shared claim is read-only — hide "Add evidence". */
  viewing?: boolean;
}

export function Sidebar({ active, userName, branch, subState, viewing }: SidebarProps) {
  const initial = (userName.trim()[0] ?? "U").toUpperCase();
  // Only a confirmed free user sees the upsell. On "error" (unknown) we hide it
  // to avoid baiting a Pro user toward a second checkout during an outage.
  const showUpgrade = subState === "free";
  return (
    <aside className={styles.side}>
      <div className={styles.brand}>
        <span className={styles.mark}>
          <Icon name="shield" size={22} stroke={2.1} />
        </span>
        <span className={styles.brandText}>
          After Duty
          <small>Organize. Understand. Move forward.</small>
        </span>
      </div>

      {/* The Documents page IS the evidence hub now (P2-1: upload + write a
          statement + describe to AI). This stays a prominent desktop CTA — it
          navigates there instead of opening the dissolved AddModal. */}
      {!viewing && (
        <Link href="/documents" className={styles.addBtn}>
          <Icon name="plus" size={19} stroke={2.4} /> Add evidence
        </Link>
      )}

      <nav className={styles.nav} aria-label="Primary">
        {NAV.map((it) => (
          <Link
            key={it.href}
            href={it.href}
            className={styles.navItem}
            data-active={active === it.href ? "1" : "0"}
            aria-current={active === it.href ? "page" : undefined}
          >
            <Icon name={it.icon} size={21} stroke={active === it.href ? 2.3 : 2} />
            {it.label}
          </Link>
        ))}
      </nav>

      <div className={styles.foot}>
        {showUpgrade && (
          <Link href="/upgrade" className={styles.pro}>
            <span className={styles.proIc}>
              <Icon name="sparkle" size={18} />
            </span>
            <span className={styles.proTx}>
              <b>Upgrade to Pro</b>
              <small>AI synthesis &amp; gap analysis</small>
            </span>
          </Link>
        )}
        <Link href="/profile" className={styles.userChip}>
          <span className={styles.avatar}>{initial}</span>
          <span className={styles.userTx}>
            {/* Ellipsized at 264px — the title carries the full name. */}
            <b title={userName}>{userName}</b>
            {branch && <small>{branch}</small>}
          </span>
          <Icon name="chevDown" size={16} />
        </Link>
      </div>
    </aside>
  );
}
