import { loadAnalysisUpdates, loadConditionsPage } from "@/lib/api/endpoints";
import { ConditionsList } from "@/components/conditions/ConditionsList";
import { ConditionsEmpty } from "@/components/conditions/ConditionsEmpty";
import { NATIVE } from "@/lib/platform";
import { NativeConditions } from "@/components/native/NativeConditions";

export default async function ConditionsPage() {
  if (NATIVE) return <NativeConditions />;

  // `updates` marks the rows the latest unread analysis update changed
  // ("Updated" pills); the journal read degrades to empty internally on failure.
  const [{ conditions, subState, documentsCount }, updates] = await Promise.all([
    loadConditionsPage(),
    loadAnalysisUpdates(),
  ]);
  if (conditions.length === 0) {
    // Subscription-aware copy (P1-16): free-with-docs gets the honest Pro
    // boundary; "error" NEVER selects the free copy.
    return <ConditionsEmpty subState={subState} documentsCount={documentsCount} />;
  }
  return <ConditionsList conditions={conditions} updatedIds={updates.changedConditionIds} />;
}
