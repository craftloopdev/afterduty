import { loadNextSteps } from "@/lib/api/endpoints";
import { NextStepsTabs } from "@/components/steps/NextStepsTabs";
import { StepsEmpty } from "@/components/steps/StepsEmpty";
import { NATIVE } from "@/lib/platform";
import { NativeSteps } from "@/components/native/NativeSteps";

export default async function StepsPage() {
  if (NATIVE) return <NativeSteps />;

  const vm = await loadNextSteps();
  if (vm.steps.length === 0 && vm.readyCount === 0) {
    // Mid-re-run the gaps are being recomputed — "add evidence" would be a
    // false instruction; the tabs render the honest "Re-checking…" state.
    if (vm.gapAnalysisPending || vm.pipelineActive) {
      return <NextStepsTabs vm={vm} />;
    }
    // Subscription-aware copy (P1-16): free-with-docs gets the honest Pro
    // boundary; "error" NEVER selects the free copy.
    return <StepsEmpty subState={vm.subState} documentsCount={vm.documentsCount} />;
  }
  return <NextStepsTabs vm={vm} />;
}
