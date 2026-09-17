"use client";

import Link from "next/link";
import { DisclaimerPill } from "@/components/ui/DisclaimerPill";
import styles from "./TopBar.module.css";

interface TopBarProps {
  title: string;
  sub?: string | null;
  initial: string;
  onDisclaimer: () => void;
}

export function TopBar({ title, sub, initial, onDisclaimer }: TopBarProps) {
  return (
    <header className={styles.top}>
      <div className={styles.titles}>
        <h1 className={styles.title}>{title}</h1>
        {sub && <p className={styles.sub}>{sub}</p>}
      </div>
      <div className={styles.actions}>
        {/* "Add evidence" now lives on the Documents page (P2-1): the Docs page
            IS the evidence hub — upload + write-a-statement + describe-to-AI. */}
        <DisclaimerPill onClick={onDisclaimer} />
        <Link href="/profile" className={styles.avatar} aria-label="Profile & settings">
          {initial}
        </Link>
      </div>
    </header>
  );
}
