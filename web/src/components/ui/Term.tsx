"use client";

import { useId, useState } from "react";
import { triadLeg } from "@/lib/theme/tokens";
import styles from "./Term.module.css";

// Inline jargon popover (P1-18). Usage: <Term k="nexus">nexus letter</Term>.
// The trigger is a real <button> (keyboard + AT reachable) with aria-expanded;
// the definition renders inline as a positioned note the button describes.
// All content is static, veteran-plain language (6th–8th grade) — $0 model
// spend. The triad-leg entries reuse the canonical leg descriptions from
// lib/theme/tokens so this dictionary can never drift from the triad UI.

export type TermKey =
  | "nexus"
  | "diagnosis"
  | "in-service"
  | "severity"
  | "vasrd"
  | "dc-code"
  | "presumptive"
  | "pyramiding"
  | "cp-exam"
  | "itf"
  | "effective-date";

export interface TermEntry {
  title: string;
  def: string;
}

export const TERMS: Record<TermKey, TermEntry> = {
  nexus: {
    title: "Nexus",
    // Reuses the triad leg description ("A medical link between the two").
    def:
      `${triadLeg("nx").desc} — a doctor's written opinion connecting your ` +
      "condition to your service. Usually a short letter, often called a nexus letter.",
  },
  diagnosis: {
    title: "Diagnosis",
    def:
      `${triadLeg("dx").desc} — a medical provider has named the condition in ` +
      "your records. The first leg of every claim.",
  },
  "in-service": {
    title: "In-service event",
    def:
      `${triadLeg("is").desc} — an injury, illness, exposure, or event that shows ` +
      "up in your service records or statements from people who were there.",
  },
  severity: {
    title: "Severity",
    def:
      "How bad your condition is today. Severity is what sets the percentage — " +
      "the same condition can rate 10% or 70% depending on what the evidence shows.",
  },
  vasrd: {
    title: "VASRD",
    def:
      "The VA Schedule for Rating Disabilities — the rulebook VA uses to turn " +
      "evidence into a rating percentage. Every condition has its own criteria in it.",
  },
  "dc-code": {
    title: "Diagnostic code (DC)",
    def:
      "The number the VA rulebook gives a specific condition — for example, " +
      "sleep apnea is DC 6847. The code lists exactly what evidence earns each percentage.",
  },
  presumptive: {
    title: "Presumptive",
    def:
      "A condition the law presumes is connected to your service — like certain " +
      "burn pit or Agent Orange illnesses. If a presumption covers you, you usually " +
      "do not need a nexus letter.",
  },
  pyramiding: {
    title: "Pyramiding",
    def:
      "VA's rule against paying twice for the same symptom under two different " +
      "names. If two conditions overlap, VA rates the overlap once.",
  },
  "cp-exam": {
    title: "C&P exam",
    def:
      "A Compensation and Pension exam — a medical visit VA schedules and pays for " +
      "to check your condition. Be honest about your worst days.",
  },
  itf: {
    title: "Intent to File (ITF)",
    def:
      "A one-page form (VA Form 21-0966) that locks in your date. You then get up " +
      "to a year to finish the claim — and if VA grants it, back pay counts from the ITF date.",
  },
  "effective-date": {
    title: "Effective date",
    def:
      "The date VA starts owing you benefits — usually the day VA received your " +
      "claim or your Intent to File, whichever came first.",
  },
};

export function Term({ k, children }: { k: TermKey; children: React.ReactNode }) {
  const [open, setOpen] = useState(false);
  const id = useId();
  const t = TERMS[k];
  return (
    <span className={styles.wrap}>
      <button
        type="button"
        className={styles.term}
        aria-expanded={open}
        aria-describedby={open ? id : undefined}
        onClick={() => setOpen((o) => !o)}
        onKeyDown={(e) => {
          if (e.key === "Escape") setOpen(false);
        }}
        onBlur={() => setOpen(false)}
      >
        {children}
      </button>
      {open && (
        <span role="note" id={id} className={styles.pop}>
          <b className={styles.popTitle}>{t.title}.</b> {t.def}
        </span>
      )}
    </span>
  );
}
