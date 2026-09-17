"use client";

// Claim switcher (P0-8): when `me.sharedProfiles` is non-empty and the user is
// on their OWN claim, the shell shows this compact row offering
// "View <owner>'s claim" entries. Selecting one POSTs /api/view-as (web) or
// swaps the native module-level selection, then full-navigates/refetches so
// every read re-runs against the shared claim.

import { useState } from "react";
import { Icon } from "@/components/ui/Icon";
import type { SharedClaimRef } from "@/lib/models/vm";
import styles from "./ViewerSwitcher.module.css";

interface ViewerSwitcherProps {
  sharedClaims: SharedClaimRef[];
  /**
   * Native override: the layout sets the DirectApiClient's viewAs and
   * remounts/refetches. When absent (web), selection POSTs the BFF route and
   * full-navigates home (pinned contract).
   */
  onSelect?: (claim: SharedClaimRef) => void;
}

async function webSelect(claim: SharedClaimRef): Promise<boolean> {
  const res = await fetch("/api/view-as", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ claimId: claim.claimId }),
  });
  if (!res.ok) return false;
  window.location.assign("/");
  return true;
}

export function ViewerSwitcher({ sharedClaims, onSelect }: ViewerSwitcherProps) {
  const [failed, setFailed] = useState(false);
  if (!sharedClaims.length) return null;

  const select = async (claim: SharedClaimRef) => {
    if (onSelect) {
      onSelect(claim);
      return;
    }
    setFailed(!(await webSelect(claim).catch(() => false)));
  };

  return (
    <div className={styles.bar} data-testid="viewer-switcher">
      <span className={styles.label}>
        <Icon name="share" size={15} stroke={2.2} /> Shared with you:
      </span>
      {sharedClaims.map((c) => (
        <button key={c.claimId} className={styles.entry} onClick={() => void select(c)}>
          View {c.ownerName}&apos;s claim
        </button>
      ))}
      {failed && <span className={styles.err}>Couldn&apos;t open that claim — try again.</span>}
    </div>
  );
}
