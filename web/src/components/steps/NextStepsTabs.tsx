"use client";

import { useState } from "react";
import type { NextStepsVM } from "@/lib/models/vm";
import { PipelinePulse } from "@/components/home/PipelinePulse";
import { StepsPanel } from "./StepsPanel";
import { ScenariosPanel } from "./ScenariosPanel";
import styles from "./NextStepsTabs.module.css";

/** `onRefresh`: native loader refetch; web leaves it unset (router.refresh()). */
export function NextStepsTabs({ vm, onRefresh }: { vm: NextStepsVM; onRefresh?: () => void }) {
  const [view, setView] = useState<"steps" | "scenarios">("steps");
  return (
    <div className={styles.wrap}>
      {/* Re-run visibility (P0-5): steps must not shift silently. */}
      <PipelinePulse
        variant="banner"
        initialActive={vm.pipelineActive ?? false}
        enabled={vm.isPro ?? false}
        onComplete={onRefresh}
      />
      <div className={styles.seg} role="tablist" aria-label="Next steps view">
        <button
          role="tab"
          aria-selected={view === "steps"}
          data-active={view === "steps" ? "1" : "0"}
          onClick={() => setView("steps")}
        >
          Steps
        </button>
        <button
          role="tab"
          aria-selected={view === "scenarios"}
          data-active={view === "scenarios" ? "1" : "0"}
          onClick={() => setView("scenarios")}
        >
          Scenarios
        </button>
      </div>
      {view === "steps" ? (
        <StepsPanel
          steps={vm.steps}
          highCount={vm.highCount}
          gapAnalysisPending={vm.gapAnalysisPending ?? false}
        />
      ) : (
        <ScenariosPanel scenarios={vm.scenarios} estimateUnavailable={vm.estimateUnavailable} />
      )}
    </div>
  );
}
