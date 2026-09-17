import Link from "next/link";
import type { SubscriptionState } from "@/lib/models/vm";
import { EmptyState } from "@/components/ui/EmptyState";
import { Icon } from "@/components/ui/Icon";
import styles from "@/app/(app)/steps/steps.module.css";

/**
 * Empty state for the Next Steps screen, subscription-aware (P1-16).
 *
 * Mirrors `ConditionsEmpty`: a FREE user with documents already uploaded gets
 * the honest boundary + upgrade path, never the "Add your evidence" dead loop.
 * `subState === "error"` (honest unknown) and free-with-no-docs both fall
 * through to the neutral default — an outage must never flash the upsell.
 */
export function StepsEmpty({
  subState,
  documentsCount,
}: {
  subState?: SubscriptionState;
  documentsCount?: number;
}) {
  if (subState === "free" && (documentsCount ?? 0) > 0) {
    return (
      <EmptyState
        icon="target"
        title="Your documents are stored"
        body="AI analysis is a Pro feature — upgrade and we'll surface the highest-value steps to strengthen your claim."
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
      icon="target"
      title="No steps yet"
      body="Add your evidence and we'll surface the highest-value steps to strengthen your claim."
      action={
        <Link className={styles.cta} href="/documents">
          <Icon name="plus" size={18} stroke={2.4} /> Add evidence
        </Link>
      }
    />
  );
}
