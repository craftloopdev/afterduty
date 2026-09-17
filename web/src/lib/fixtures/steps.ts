import { populatedHomeVM } from "./home";
import type { NextStepsVM } from "@/lib/models/vm";

export const nextStepsVM: NextStepsVM = {
  steps: populatedHomeVM.steps,
  highCount: populatedHomeVM.steps.filter((s) => s.priority === "high").length,
  medCount: populatedHomeVM.steps.filter((s) => s.priority === "medium").length,
  readyCount: populatedHomeVM.ready.length,
  scenarios: [
    {
      label: "File your 3 ready conditions",
      conditionNames: ["PTSD", "Asthma", "Chronic Migraines"],
      combinedRating: 100,
      monthlyPay: 3831,
      deltaPay: 0,
      badge: "100%",
      alt: false,
    },
    {
      label: "+ Close 2 high-priority gaps",
      conditionNames: ["PTSD", "Asthma", "Chronic Migraines", "Sleep Apnea", "Tinnitus"],
      combinedRating: 100,
      monthlyPay: 3967,
      deltaPay: 136,
      badge: "100%",
      alt: true,
    },
  ],
  estimateUnavailable: false,
  subState: "pro",
  documentsCount: populatedHomeVM.documentsCount,
};

/** Scenarios with a failed pay calc — dollar figures show "Estimate unavailable". */
export const nextStepsEstimateErrorVM: NextStepsVM = {
  ...nextStepsVM,
  scenarios: nextStepsVM.scenarios.map((s) => ({
    ...s,
    monthlyPay: 0,
    deltaPay: 0,
  })),
  estimateUnavailable: true,
};
