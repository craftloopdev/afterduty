package com.afterduty.service;

import com.afterduty.model.ServiceHistoryResolution;
import com.afterduty.model.User;
import com.afterduty.repository.ServiceHistoryResolutionRepository;
import com.afterduty.repository.UserRepository;
import com.afterduty.service.ServiceHistoryReconciler.Conflict;
import com.afterduty.service.ServicePeriodDeriver.RawInputs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * PIPELINE-TIME (never read-time) LLM adjudication of genuine service-history
 * conflicts (Service History P3 Part B, design 2026-07-05).
 *
 * <p><b>Where it runs.</b> Invoked from the analysis pipeline
 * ({@code PipelineService.runFullPipeline}) alongside synthesis + gap analysis —
 * NOT from the read-time {@code GET /api/auth/profile} path. The profile endpoint
 * only ever READS a persisted {@link ServiceHistoryResolution} (fast, deterministic);
 * this service is what WRITES those rows.
 *
 * <p><b>What it does.</b> Rebuilds the user's raw reconciliation corpus, asks the
 * reconciler to {@link ServiceHistoryReconciler#detectConflicts detect genuine
 * conflicts} (two EQUAL-authority sources with contradictory dates), and for each
 * flagged conflict calls the LLM to pick a value + reason, persisting the result
 * keyed by {@code (userId, clusterKey)} with overwrite-by-key semantics. The
 * conclusion's deterministic pick stays the default; the resolution is applied at
 * read time BELOW a veteran override.
 *
 * <p><b>Safe-by-default flag.</b> Gated by {@code va-claim.service-history.llm-adjudication}
 * (env {@code SERVICE_HISTORY_LLM_ADJUDICATION}, DEFAULT ON). Even ON, NO LLM call
 * happens unless a genuine conflict exists — the common case (no equal-authority
 * contradiction) short-circuits to zero cost. The veteran override (Part A) is NOT
 * flagged and is always available regardless of this switch.
 *
 * <p><b>Staleness.</b> Each resolution stores the cluster's evidence fingerprint;
 * the reconciler ignores a resolution whose fingerprint no longer matches (the
 * documents moved), and a re-run here simply overwrites the (userId, clusterKey)
 * row — keeping it simple and never leaving a stale LLM answer applied.
 */
@Service
public class ServiceHistoryAdjudicationService {

    private static final Logger log = LoggerFactory.getLogger(ServiceHistoryAdjudicationService.class);

    /**
     * The LLM seam. Given a genuine conflict, return the chosen value + reasoning,
     * or {@link Optional#empty()} to leave the deterministic pick in place. Kept as
     * a narrow interface so the pipeline step is unit-testable with a fake — the
     * tests exercise conflict detection + persistence + flag/staleness WITHOUT a
     * real network call. The production bean is {@link GeminiAdjudicator}.
     */
    @FunctionalInterface
    public interface Adjudicator {
        Optional<Adjudication> adjudicate(Conflict conflict, Long claimId, Long userId);
    }

    /** An LLM verdict for one conflict: the chosen value (must be one of the two
     *  contradictory values) + a short human-readable why. */
    public record Adjudication(String chosenValue, String reasoning) {
    }

    /**
     * Master switch (design 2026-07-05). DEFAULT ON but safe: no conflict ⇒ no LLM
     * call. Set {@code SERVICE_HISTORY_LLM_ADJUDICATION=false} to disable
     * adjudication entirely (persisted resolutions still READ; no new ones written).
     */
    @Value("${va-claim.service-history.llm-adjudication:true}")
    private boolean adjudicationEnabled = true;

    private final ServicePeriodDeriver deriver;
    private final ServiceHistoryReconciler reconciler;
    private final ServiceHistoryResolutionRepository resolutionRepository;
    private final UserRepository userRepository;
    private final Adjudicator adjudicator;

    public ServiceHistoryAdjudicationService(ServicePeriodDeriver deriver,
                                             ServiceHistoryReconciler reconciler,
                                             ServiceHistoryResolutionRepository resolutionRepository,
                                             UserRepository userRepository,
                                             Adjudicator adjudicator) {
        this.deriver = deriver;
        this.reconciler = reconciler;
        this.resolutionRepository = resolutionRepository;
        this.userRepository = userRepository;
        this.adjudicator = adjudicator;
    }

    /** Test/override hook for the flag (Spring sets it via @Value). */
    void setAdjudicationEnabled(boolean adjudicationEnabled) {
        this.adjudicationEnabled = adjudicationEnabled;
    }

    /**
     * Adjudicate service-history conflicts for a user (pipeline-time). Never throws
     * to the caller — a failure here must not abort synthesis/gap analysis. Returns
     * the number of conflicts adjudicated + persisted (0 in the common no-conflict
     * or flag-off case). Safe to call on every analysis run.
     */
    public int adjudicateForUser(Long claimId, Long userId) {
        if (!adjudicationEnabled) {
            log.debug("Service-history LLM adjudication disabled (flag off) for user {}", userId);
            return 0;
        }
        try {
            Optional<User> u = userRepository.findById(userId);
            if (u.isEmpty()) return 0;

            RawInputs inputs = deriver.buildRawInputsForUser(u.get());
            List<Conflict> conflicts = reconciler.detectConflicts(inputs.raw(), inputs.classifications());
            if (conflicts.isEmpty()) {
                // The common case: authority resolves everything deterministically —
                // NO LLM call, zero cost.
                return 0;
            }
            log.info("Service-history: {} genuine conflict(s) to adjudicate for user {} (claim {})",
                    conflicts.size(), userId, claimId);

            int persisted = 0;
            for (Conflict conflict : conflicts) {
                if (adjudicateOne(conflict, claimId, userId)) persisted++;
            }
            return persisted;
        } catch (RuntimeException e) {
            // Best-effort: never fail the pipeline over service-history adjudication.
            log.warn("Service-history adjudication failed for user {} (claim {}): {}",
                    userId, claimId, e.getMessage());
            return 0;
        }
    }

    /** Adjudicate + persist ONE conflict. Returns true when a resolution was
     *  written. The LLM's choice must be one of the two contradictory values —
     *  a hallucinated third value is rejected (deterministic pick stands). */
    private boolean adjudicateOne(Conflict conflict, Long claimId, Long userId) {
        Optional<Adjudication> verdict = adjudicator.adjudicate(conflict, claimId, userId);
        if (verdict.isEmpty()) return false;
        String chosen = verdict.get().chosenValue();
        if (chosen == null
                || (!chosen.equals(conflict.valueA()) && !chosen.equals(conflict.valueB()))) {
            log.warn("Service-history adjudication returned an out-of-set value '{}' for {} "
                            + "(expected {} or {}); keeping deterministic pick",
                    chosen, conflict.field(), conflict.valueA(), conflict.valueB());
            return false;
        }
        // Overwrite-by-key: one resolution per (userId, clusterKey).
        ServiceHistoryResolution row = resolutionRepository
                .findByUserIdAndClusterKey(userId, conflict.clusterKey())
                .orElseGet(ServiceHistoryResolution::new);
        row.setUserId(userId);
        row.setClusterKey(conflict.clusterKey());
        row.setResolvedField(conflict.field());
        row.setResolvedValue(chosen);
        row.setReasoning(verdict.get().reasoning());
        row.setEvidenceFingerprint(conflict.evidenceFingerprint());
        row.setCreatedAt(Instant.now());
        resolutionRepository.save(row);
        return true;
    }
}
