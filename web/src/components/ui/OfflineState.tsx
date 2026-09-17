"use client";

import { Icon } from "./Icon";
import { Button } from "./Button";
import styles from "./OfflineState.module.css";

// Branded offline state (capacitor-ios-spec §D.3/§H.5 — REQUIRED). Distinct from
// the generic `ErrorState`: a brand shield mark + the spec's friendly copy + a
// Retry. Shown whenever a native data load fails at the connectivity level (the
// app boots from a static bundle with zero network, so a cold airplane-mode
// launch must reach THIS, never a white screen or a raw error). The recording of
// airplane-launch → this state → Retry → Home is mandatory evidence (§H.5).

interface OfflineStateProps {
  onRetry?: () => void;
}

export function OfflineState({ onRetry }: OfflineStateProps) {
  return (
    <div className={styles.root}>
      <span className={styles.mark} aria-hidden="true">
        <Icon name="shield" size={30} stroke={1.9} />
      </span>
      <h3 className={styles.title}>You&rsquo;re offline</h3>
      <p className={styles.body}>After Duty needs a connection to load your claim.</p>
      {onRetry && (
        <Button variant="primary" size="sm" icon="bolt" onClick={onRetry}>
          Retry
        </Button>
      )}
    </div>
  );
}
