"use client";

import { Icon } from "@/components/ui/Icon";
import type { FeatureVM } from "@/lib/models/vm";
import styles from "./PaywallView.module.css";

// Presentational pieces shared by BOTH paywall branches (web Stripe + native
// RevenueCat). Kept Stripe-free so the web-only `StripePaywall` module can be
// fully tree-shaken from the native export without dragging the native paywall's
// shared furniture with it (§H.4 anti-steering — the §H.4 bundle audit greps
// `web/out/` for Stripe strings, none of which live here).

// §6.5 outcome-language hero copy — shared by BOTH branches so the web and
// native paywalls can never drift. Sells outcomes ("see exactly how to close
// your evidence gaps"), never engineering nouns ("AI extraction pipeline").
// NOTE: this copy renders inside the NATIVE paywall too, where the §H.4
// anti-steering test asserts NO /web/i and NO /stripe/i text — keep both
// substrings out of everything below.
export const PAYWALL_HERO_TITLE = "See exactly how to close your evidence gaps";
export const PAYWALL_HERO_SUB =
  "Pro finds what's missing for each condition and gives you a step-by-step plan to strengthen your claim.";

export function FeaturesList({ features, flush }: { features: FeatureVM[]; flush?: boolean }) {
  return (
    <div className={flush ? styles.featuresFlush : styles.card}>
      <div className={styles.featuresTitle}>Everything in Pro</div>
      <ul className={styles.featureList}>
        {features.map((f) => (
          <li key={f.key} className={styles.featureRow}>
            <span className={styles.featureCheck} aria-hidden="true">
              <Icon name="check" size={14} stroke={3} />
            </span>
            <span className={styles.featureTx}>
              <b className={styles.featureName}>{f.name}</b>
              <small className={styles.featureDesc}>{f.desc}</small>
            </span>
          </li>
        ))}
      </ul>
    </div>
  );
}

// What stays free — stated plainly on the paywall so the boundary is honest
// (§6.5 / P1-18 "Free forever" block). Only lists things that ARE free today.
const FREE_FOREVER: { name: string; desc: string }[] = [
  {
    name: "Your evidence, stored and organized",
    desc: "Upload your records and keep them safe in one place.",
  },
  {
    name: "Statements in your own words",
    desc: "Add what happened — it becomes part of your evidence.",
  },
  {
    name: "Every guide in Learn",
    desc: "C&P exam prep, gathering records, and free help from VSOs.",
  },
];

export function FreeForeverCard() {
  return (
    <div className={styles.card}>
      <div className={styles.featuresTitle}>Free forever</div>
      <ul className={styles.featureList}>
        {FREE_FOREVER.map((f) => (
          <li key={f.name} className={styles.featureRow}>
            <span className={styles.featureCheck} aria-hidden="true">
              <Icon name="check" size={14} stroke={3} />
            </span>
            <span className={styles.featureTx}>
              <b className={styles.featureName}>{f.name}</b>
              <small className={styles.featureDesc}>{f.desc}</small>
            </span>
          </li>
        ))}
      </ul>
      <p className={styles.freeNote}>No card required. What&rsquo;s free stays free.</p>
    </div>
  );
}

/** Mission framing + hardship note (§6.5): Pro is how the free tier stays
 *  funded, and no veteran gets priced out of help. */
export function MissionNote() {
  return (
    <div className={styles.mission}>
      <p className={styles.missionLine}>Pro keeps After Duty free for other veterans.</p>
      <p className={styles.missionHardship}>
        Can&rsquo;t afford it?{" "}
        <a href="mailto:support@afterduty.app">Email us at support@afterduty.app</a> &mdash;
        no veteran gets turned away.
      </p>
    </div>
  );
}
