"use client";

import Link from "next/link";
import { Icon } from "@/components/ui/Icon";
import type { CiteTarget } from "@/lib/cite";
import styles from "./CitationChip.module.css";

// Inline citation pill rendered by the Markdown link renderer when a link uses
// the `cite:` scheme (spec §G.5). CFR → new-tab eCFR link (book/shield icon);
// doc → in-app Link to the documents page (file icon). Token-only styling,
// ≥24px tap target, labeled for screen readers.

export function CitationChip({ target, label }: { target: CiteTarget; label: string }) {
  const text = label.trim() || (target.kind === "cfr" ? `38 CFR § ${target.section}` : "your document");
  const aria = `Source: ${text}`;

  if (target.kind === "cfr") {
    return (
      <a
        className={styles.chip}
        href={target.href}
        target="_blank"
        rel="noopener noreferrer"
        aria-label={aria}
      >
        <Icon name="shield" size={13} stroke={2.2} className={styles.ic} />
        <span className={styles.txt}>{text}</span>
      </a>
    );
  }

  return (
    <Link className={styles.chip} href={target.href} aria-label={aria}>
      <Icon name="doc2" size={13} stroke={2.2} className={styles.ic} />
      <span className={styles.txt}>{text}</span>
    </Link>
  );
}
