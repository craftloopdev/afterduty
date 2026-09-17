"use client";

import { Suspense } from "react";
import { useSearchParams } from "next/navigation";
import { ThemeProvider } from "@/components/shell/ThemeProvider";
import { AppShell } from "@/components/shell/AppShell";
import { NextStepsTabs } from "@/components/steps/NextStepsTabs";
import { nextStepsVM, nextStepsEstimateErrorVM } from "@/lib/fixtures/steps";

// Dev preview via ?v=:
//   (default)       normal scenarios with dollar figures
//   estimate-error  pay calc failed → "Estimate unavailable" in the Scenarios tab
// Dev routes 404 in production (handled globally). Variant read CLIENT-side so
// the route exports statically for the native capture build (§F.2).
export default function DevStepsPage() {
  return (
    <Suspense fallback={null}>
      <DevStepsInner />
    </Suspense>
  );
}

function DevStepsInner() {
  const v = useSearchParams().get("v") ?? undefined;
  const vm = v === "estimate-error" ? nextStepsEstimateErrorVM : nextStepsVM;
  return (
    <ThemeProvider>
      <AppShell userName="Griff" branch="U.S. Army · SGT" subState="free">
        <NextStepsTabs vm={vm} />
      </AppShell>
    </ThemeProvider>
  );
}
