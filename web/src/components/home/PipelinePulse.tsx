"use client";

import { useEffect, useRef, useState, useSyncExternalStore } from "react";
import { useRouter } from "next/navigation";
import { Icon } from "@/components/ui/Icon";
import {
  analysisPulseArmedAt,
  deriveStages,
  disarmAnalysisPulse,
  fetchJobs,
  jobsActive,
  subscribeAnalysisPulse,
  type JobsSnapshot,
} from "@/lib/jobs";
import styles from "./PipelinePulse.module.css";

const POLL_MS = 12_000;
// Two consecutive idle polls before we call a run finished: the 15s scheduler
// can leave a short idle window BETWEEN stages (extraction done, synthesis not
// yet picked up) that a single poll would misread as completion.
const IDLE_POLLS_TO_COMPLETE = 2;
const serverArmedAt = () => 0; // SSR/hydration: never armed on the server

export interface PipelinePulseProps {
  /**
   * `screen` — the first-analysis takeover card (no conditions yet):
   * indeterminate bar, honest timing copy, per-stage checklist. Polls while
   * mounted (the server only mounts it when a run is genuinely active).
   * `banner` — the slim re-run strip for veterans who already have results:
   * renders nothing until armed (server hint or a fresh upload), then shows
   * "Analyzing your new evidence…" until the run completes.
   */
  variant: "screen" | "banner";
  /** Server-side hint that a run is active right now (arms banner polling). */
  initialActive?: boolean;
  /**
   * Master switch — false for users whose pipeline never advances (free tier),
   * so an upload never arms a poll loop that cannot complete.
   */
  enabled?: boolean;
  /**
   * Called when a run completes so fresh numbers arrive WITH the acknowledgment.
   * Web leaves it unset (router.refresh()); native passes its loader's refetch.
   */
  onComplete?: () => void;
}

export function PipelinePulse({
  variant,
  initialActive = false,
  enabled = true,
  onComplete,
}: PipelinePulseProps) {
  // The refresh function (not the router object) keeps the effect deps stable.
  const { refresh } = useRouter();
  // A recent upload (possibly on another screen) arms the banner. External
  // store keeps hydration clean: the server render is never armed.
  const uploadArmedAt = useSyncExternalStore(
    subscribeAnalysisPulse,
    analysisPulseArmedAt,
    serverArmedAt,
  );
  // A banner stands down (completion or stale arm) by dismissing what armed it;
  // a NEWER upload (greater timestamp) re-arms past the dismissal.
  const [dismissedUpTo, setDismissedUpTo] = useState(0);
  const [initialDismissed, setInitialDismissed] = useState(false);
  const [jobs, setJobs] = useState<JobsSnapshot | null>(null);
  // Completion = an idle streak AFTER a poll observed the run active. Server
  // hints alone must not trigger refreshes — a stale `analysisStage` on an
  // idle claim would loop forever.
  const sawActive = useRef(false);
  const idleStreak = useRef(0);

  const armed =
    enabled &&
    (variant === "screen" ||
      (initialActive && !initialDismissed) ||
      uploadArmedAt > dismissedUpTo);

  useEffect(() => {
    if (!armed) return;
    let stopped = false;

    const standDown = () => {
      idleStreak.current = 0;
      disarmAnalysisPulse();
      if (variant === "banner") {
        setInitialDismissed(true);
        setDismissedUpTo(Date.now());
      }
    };

    const tick = async () => {
      const snapshot = await fetchJobs();
      if (stopped || snapshot == null) return; // transient error — try next tick
      setJobs(snapshot);
      if (jobsActive(snapshot)) {
        sawActive.current = true;
        idleStreak.current = 0;
        return;
      }
      idleStreak.current += 1;
      if (idleStreak.current < IDLE_POLLS_TO_COMPLETE) return;
      if (sawActive.current) {
        // Observed run finished — refresh so new numbers land with the
        // banner's acknowledgment, not silently.
        sawActive.current = false;
        standDown();
        if (onComplete) onComplete();
        else refresh();
      } else {
        // Armed but nothing was ever running (stale arm flag / stale stage) —
        // stand down quietly, no refresh.
        standDown();
      }
    };

    tick();
    const id = setInterval(tick, POLL_MS);
    return () => {
      stopped = true;
      clearInterval(id);
    };
  }, [armed, variant, onComplete, refresh]);

  if (!enabled) return null;

  if (variant === "screen") return <AnalyzingScreen jobs={jobs} />;

  // Banner: only visible while a run is (believed) active. Before the first
  // poll answers, trust the arm signal — an upload always starts work.
  const visible = armed && (jobs == null || jobsActive(jobs));
  if (!visible) return null;
  return (
    <div className={styles.banner} role="status">
      <span className={styles.bannerDot} aria-hidden="true" />
      <b>Analyzing your new evidence&hellip;</b>
      <small>Your conditions and numbers will update when it finishes.</small>
    </div>
  );
}

/** First-analysis takeover: indeterminate, honest, with a live stage checklist. */
function AnalyzingScreen({ jobs }: { jobs: JobsSnapshot | null }) {
  const stages = deriveStages(jobs);
  return (
    <div className={styles.wrap}>
      <span className={styles.ic}>
        <Icon name="sparkle" size={28} />
      </span>
      <h2 className={styles.title}>We&apos;re analyzing your records</h2>
      <p className={styles.body}>
        This usually takes 5&ndash;15 minutes. You can leave this page &mdash; we&apos;ll keep
        working and your results will appear here.
      </p>
      <div className={styles.track} role="progressbar" aria-label="Analysis in progress">
        <span className={styles.sweep} />
      </div>
      <ul className={styles.stages}>
        {stages.map((s) => (
          <li key={s.key} className={styles.stage} data-state={s.state}>
            <span className={styles.stageIc}>
              {s.state === "done" ? (
                <Icon name="check" size={14} stroke={3} />
              ) : (
                <Icon name="clock" size={14} stroke={2.4} />
              )}
            </span>
            {s.label}
          </li>
        ))}
      </ul>
    </div>
  );
}
