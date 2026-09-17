import Link from "next/link";
import type { HomeVM } from "@/lib/models/vm";
import type { AnalysisUpdatesVM } from "@/lib/notifications";
import { Icon } from "@/components/ui/Icon";
import { EmptyState } from "@/components/ui/EmptyState";
import { HomeHero } from "./HomeHero";
import { DoThisNext } from "./DoThisNext";
import { ConditionsPreview } from "./ConditionsPreview";
import { EvidenceHealth } from "./EvidenceHealth";
import { PayBreakdown } from "./PayBreakdown";
import { ShareCard } from "./ShareCard";
import { PipelinePulse } from "./PipelinePulse";
import { HomeUpdates } from "./WhatChangedCard";
import { NameCaptureCard } from "./NameCaptureCard";
import styles from "./HomeView.module.css";

/**
 * Pure presentational Home — fed by either loadHomeVM() (live) or a fixture
 * (dev). `onRefresh` is the native loaders' refetch (web leaves it unset —
 * PipelinePulse falls back to router.refresh()). `updates` is the unread
 * claim-journal view (server-loaded on web, a parallel loader on native);
 * absent means "no journal" and nothing extra renders.
 */
export function HomeView({
  vm,
  updates,
  onRefresh,
}: {
  vm: HomeVM;
  updates?: AnalysisUpdatesVM;
  onRefresh?: () => void;
}) {
  // "What should we call you?" — only for accounts with NO usable display
  // name (loader's `nameless` flag; the card also honors its own per-tab
  // dismissal). Mounted on the settled states, never over an error/progress
  // screen. `onSaved` is the native refetch; web falls back to router.refresh.
  const nameCard = <NameCaptureCard show={vm.nameless === true} onSaved={onRefresh} />;

  // /learn entry for FREE users only (P1-18) — tri-state rule: "error" is an
  // honest unknown, so it never renders free-targeted affordances.
  const learnCard = (centered = false) =>
    vm.subState === "free" && (
      <Link
        className={[styles.learnCard, centered && styles.learnCardCenter]
          .filter(Boolean)
          .join(" ")}
        href="/learn"
      >
        <Icon name="info" size={18} stroke={2.2} />
        <span>
          New to VA claims? <b>Start with our guides</b>
        </span>
        <Icon name="chevron" size={16} stroke={2.2} />
      </Link>
    );

  // A failed run is terminal: an honest error card, never an eternal spinner.
  if (vm.analysisError) {
    return (
      <div className={styles.page}>
        <EmptyState
          icon="alert"
          title="We hit a problem analyzing your evidence"
          body={
            (vm.analysisErrorMessage ? vm.analysisErrorMessage + " " : "") +
            "Your documents are safe. Try adding your evidence again — if this keeps happening, contact support and we'll sort it out."
          }
          action={
            <Link className={styles.cta} href="/documents">
              <Icon name="file" size={18} stroke={2.4} /> Review your documents
            </Link>
          }
        />
      </div>
    );
  }

  // Analyzing is checked before the empty case: a claim mid-pipeline isn't a
  // "new user". PipelinePulse polls the jobs endpoint and refreshes on finish.
  if (vm.isAnalyzing) {
    return (
      <div className={styles.page}>
        <PipelinePulse variant="screen" initialActive onComplete={onRefresh} />
      </div>
    );
  }

  // Free user who uploaded evidence but whose AI analysis is Pro-gated: show
  // their REAL state — documents stored, working docs-only sharing, room to add
  // more — alongside the upgrade path. Never an empty lock screen.
  if (vm.analysisLocked) {
    return (
      <div className={styles.page}>
        {nameCard}
        <section className={styles.lockedCard}>
          <span className={styles.lockedIc}>
            <Icon name="file" size={24} stroke={2.1} />
          </span>
          <div className={styles.lockedTx}>
            <b>
              {vm.documentsCount > 0
                ? `${vm.documentsCount} document${vm.documentsCount === 1 ? "" : "s"} stored`
                : "Your evidence is stored"}
            </b>
            <p>
              Your records are safely stored and ready to share. Upgrade to Pro and our AI will
              read them, identify your conditions, and score each on the VA triad.
            </p>
          </div>
          <div className={styles.lockedActs}>
            <Link className={styles.cta} href="/upgrade">
              <Icon name="sparkle" size={18} stroke={2.4} /> Upgrade to run AI analysis
            </Link>
            <Link className={styles.ctaGhost} href="/documents">
              <Icon name="plus" size={18} stroke={2.4} /> Add more documents
            </Link>
          </div>
        </section>
        <div className={styles.lockedRail}>
          <ShareCard docsOnly />
        </div>
        {learnCard()}
      </div>
    );
  }

  // Analysis finished but found nothing to build on — a real terminal state
  // with a way forward, not a spinner and not the blank new-user pitch.
  if (vm.noConditionsFound) {
    return (
      <div className={styles.page}>
        <EmptyState
          icon="file"
          title="We couldn't identify conditions from these documents yet"
          body="We read everything you uploaded, but didn't find enough to build a condition. Medical records with a diagnosis, service treatment records, and personal statements about your symptoms usually work best."
          action={
            <Link className={styles.cta} href="/documents">
              <Icon name="plus" size={18} stroke={2.4} /> Add more evidence
            </Link>
          }
        />
      </div>
    );
  }

  if (vm.isNewUser) {
    return (
      <div className={styles.page}>
        {nameCard}
        <EmptyState
          icon="shield"
          title="Let's build your claim"
          body="Upload your DD-214, medical records, and statements. We'll extract the facts, find your conditions, and score each on the VA triad."
          action={
            <Link className={styles.cta} href="/documents">
              <Icon name="plus" size={18} stroke={2.4} /> Add your evidence
            </Link>
          }
        />
        {learnCard(true)}
      </div>
    );
  }

  return (
    <div className={styles.page}>
      {nameCard}

      {/* Re-run visibility: while new evidence is being analyzed, say so —
          numbers must never shift silently. */}
      <PipelinePulse
        variant="banner"
        initialActive={vm.pipelineActive}
        enabled={vm.isPro}
        onComplete={onRefresh}
      />

      {/* What changed since last visit — the digest card (or the discreet
          timeline link) sits ABOVE the hero so an update is acknowledged
          before any number is read (P1-8). */}
      {updates && <HomeUpdates updates={updates} />}

      <HomeHero
        readyScope={vm.readyScope}
        allScope={vm.allScope}
        readyCount={vm.ready.length}
        needsWorkCount={vm.needsWork.length}
        presumptiveCount={vm.presumptiveCount}
        totalConditions={vm.conditions.length}
        estimateUnavailable={vm.estimateUnavailable}
      />

      <div className={styles.grid}>
        <div className={styles.main}>
          <DoThisNext steps={vm.steps} />
          <ConditionsPreview
            conditions={vm.conditions}
            readyCount={vm.ready.length}
            needsWorkCount={vm.needsWork.length}
            notFilingCount={vm.notFilingCount}
          />
        </div>

        <aside className={styles.rail}>
          <EvidenceHealth legs={vm.evidenceHealth} />
          <PayBreakdown
            readyScope={vm.readyScope}
            allScope={vm.allScope}
            totalConditions={vm.conditions.length}
            ratesYear={vm.ratesYear}
            estimateUnavailable={vm.estimateUnavailable}
          />
          <ShareCard />
        </aside>
      </div>

      <p className={styles.footNote}>
        <Icon name="info" size={14} /> Pay estimates use the current VA compensation rates for a
        veteran with no dependents, and VA math (not simple addition). Your actual amount depends on
        dependents and any SMC. AI-assisted &amp; educational — not legal advice.
      </p>
    </div>
  );
}
