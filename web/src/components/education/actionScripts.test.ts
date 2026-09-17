import { describe, it, expect } from "vitest";
import {
  GAP_ACTION_SCRIPTS,
  gapActionScript,
  type GapTypeToken,
} from "./actionScripts";

// P1-18 per-gap-type action scripts: static "exactly what to do" guidance the
// steps UI consumes. The lookup must accept the pipeline's raw tokens, the
// /claim/gaps endpoint's humanized display strings, and legacy labels.

const ALL_TOKENS: GapTypeToken[] = [
  "nexus_letter",
  "imo_independent_medical_opinion",
  "c_and_p_exam_request",
  "buddy_statement",
  "treatment_record",
  "medication_log",
  "specialist_opinion",
  "imaging_study",
  "lab_test",
  "pharmacy_record",
  "service_record",
  "personal_statement",
  "presumptive_documentation",
];

describe("GAP_ACTION_SCRIPTS — coverage and shape", () => {
  it("covers every gap-type token the pipeline can emit", () => {
    for (const token of ALL_TOKENS) {
      const s = GAP_ACTION_SCRIPTS[token];
      expect(s, token).toBeDefined();
      expect(s.token).toBe(token);
      expect(s.title.length).toBeGreaterThan(0);
      expect(s.what.length).toBeGreaterThan(20);
      expect(s.steps.length).toBeGreaterThanOrEqual(3);
      for (const step of s.steps) expect(step.length).toBeGreaterThan(10);
      expect(s.whoToAsk.length).toBeGreaterThan(0);
      expect(s.typicalCost.length).toBeGreaterThan(0);
      expect(s.typicalTime.length).toBeGreaterThan(0);
    }
  });

  it("VA-covered evidence is stated as free; forms are named where they exist", () => {
    expect(GAP_ACTION_SCRIPTS.c_and_p_exam_request.typicalCost).toMatch(/free/i);
    expect(GAP_ACTION_SCRIPTS.service_record.typicalCost).toMatch(/free/i);
    expect(GAP_ACTION_SCRIPTS.buddy_statement.form).toBe("VA Form 21-10210");
    expect(GAP_ACTION_SCRIPTS.personal_statement.form).toBe("VA Form 21-4138");
    expect(GAP_ACTION_SCRIPTS.service_record.form).toBe("SF-180");
  });

  it("never tells a presumptive veteran to buy a nexus letter (P1-2 alignment)", () => {
    const p = GAP_ACTION_SCRIPTS.presumptive_documentation;
    expect(p.steps.join(" ")).toMatch(/do NOT need a nexus letter/i);
  });
});

describe("gapActionScript — token, humanized, and legacy lookups", () => {
  it("accepts raw pipeline tokens", () => {
    expect(gapActionScript("nexus_letter")?.token).toBe("nexus_letter");
    expect(gapActionScript("presumptive_documentation")?.token).toBe(
      "presumptive_documentation",
    );
  });

  it("accepts the /claim/gaps humanized display strings (StepVM.type as-is)", () => {
    // These are exactly what the endpoint's humanizeGapType emits.
    expect(gapActionScript("Nexus letter")?.token).toBe("nexus_letter");
    expect(gapActionScript("C&P exam request")?.token).toBe("c_and_p_exam_request");
    expect(gapActionScript("Independent medical opinion")?.token).toBe(
      "imo_independent_medical_opinion",
    );
    expect(gapActionScript("Buddy statement")?.token).toBe("buddy_statement");
    expect(gapActionScript("Presumptive documentation")?.token).toBe(
      "presumptive_documentation",
    );
    expect(gapActionScript("Service record")?.token).toBe("service_record");
  });

  it("accepts legacy synthesis tokens and pass-through labels", () => {
    expect(gapActionScript("medical_record")?.token).toBe("treatment_record");
    expect(gapActionScript("c_and_p_exam")?.token).toBe("c_and_p_exam_request");
    expect(gapActionScript("Nexus")?.token).toBe("nexus_letter");
    expect(gapActionScript("In-Service")?.token).toBe("service_record");
  });

  it("returns null for unknown or empty types — callers render nothing, never guess", () => {
    expect(gapActionScript("time_machine")).toBeNull();
    expect(gapActionScript("")).toBeNull();
    expect(gapActionScript(null)).toBeNull();
    expect(gapActionScript(undefined)).toBeNull();
  });
});
