"use client";

// Persistent viewer-mode banner (P0-8): always visible while a shared claim is
// selected. Names the owner, states the read-only rule, offers Exit, and —
// when analysis isn't accessible — renders the HONEST access notice (docs-only
// share vs. owner-Pro lapse) instead of letting empty pages lie.

import { Icon } from "@/components/ui/Icon";
import type { ViewerVM } from "@/lib/models/vm";
import styles from "./ViewerBanner.module.css";

interface ViewerBannerProps {
  viewer: ViewerVM;
  /**
   * Native override: the layout swaps the module-level viewAs and refetches.
   * When absent (web), Exit DELETEs /api/view-as and full-navigates home so
   * every RSC read drops the X-View-As header.
   */
  onExit?: () => void;
}

async function webExit(): Promise<void> {
  try {
    await fetch("/api/view-as", { method: "DELETE" });
  } finally {
    // Full navigation (pinned contract): a client-side transition would keep
    // stale viewer data in the RSC cache.
    window.location.assign("/");
  }
}

export function ViewerBanner({ viewer, onExit }: ViewerBannerProps) {
  const notice = !viewer.canViewAnalysis ? (
    <p className={styles.notice}>
      This share includes documents only — the analysis (conditions, next steps, estimates) isn&apos;t
      shared with you.
    </p>
  ) : viewer.analysisBlocked ? (
    <p className={styles.notice}>
      {viewer.ownerName ?? "The claim owner"} needs an active Pro subscription for analysis sharing —
      documents are still available.
    </p>
  ) : null;

  return (
    <div className={styles.banner} role="status" data-testid="viewer-banner">
      <div className={styles.row}>
        <span className={styles.ic}>
          <Icon name="share" size={16} stroke={2.2} />
        </span>
        <p className={styles.text}>
          Viewing <b>{viewer.ownerName ? `${viewer.ownerName}'s` : "a shared"} claim</b>
          <span className={styles.hint}> — read-only</span>
        </p>
        <button
          className={styles.exit}
          onClick={() => (onExit ? onExit() : void webExit())}
          aria-label={`Stop viewing ${viewer.ownerName ?? "this shared"} claim`}
        >
          <Icon name="close" size={14} stroke={2.4} /> Exit — back to my claim
        </button>
      </div>
      {notice}
    </div>
  );
}
