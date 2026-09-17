import Link from "next/link";
import type { SubscriptionState } from "@/lib/models/vm";
import { EmptyState } from "@/components/ui/EmptyState";
import { Icon } from "@/components/ui/Icon";
import styles from "@/app/(app)/conditions/conditions.module.css";

/**
 * Empty state for the Conditions screen, subscription-aware (P1-16).
 *
 * A FREE user who already uploaded documents must NOT be told "Add your
 * evidence" — that's a dead loop (their docs are stored but never analyzed).
 * They see the honest boundary + the upgrade path instead. Tri-state rule:
 * `subState === "error"` is an honest unknown and NEVER selects the free copy
 * (no upsell flashed at a paying user during an outage) — it falls through to
 * the neutral default, as do free users with nothing uploaded yet.
 */
export function ConditionsEmpty({
  subState,
  documentsCount,
}: {
  subState?: SubscriptionState;
  documentsCount?: number;
}) {
  if (subState === "free" && (documentsCount ?? 0) > 0) {
    return (
      <EmptyState
        icon="conditions"
        title="Your documents are stored"
        body="AI analysis is a Pro feature — upgrade and we'll read your records, identify your conditions, and score each on the VA triad."
        action={
          <Link className={styles.cta} href="/upgrade">
            <Icon name="sparkle" size={18} stroke={2.4} /> Upgrade to run AI analysis
          </Link>
        }
      />
    );
  }
  return (
    <EmptyState
      icon="conditions"
      title="No conditions yet"
      body="Add your evidence and we'll identify your conditions and score each on the VA triad."
      action={
        <Link className={styles.cta} href="/documents">
          <Icon name="plus" size={18} stroke={2.4} /> Add evidence
        </Link>
      }
    />
  );
}
