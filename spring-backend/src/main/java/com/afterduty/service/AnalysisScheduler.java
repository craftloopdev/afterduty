package com.afterduty.service;

import com.afterduty.model.Atom;
import com.afterduty.model.Claim;
import com.afterduty.model.IdentifiedCondition;
import com.afterduty.model.User;
import com.afterduty.repository.AtomRepository;
import com.afterduty.repository.ClaimRepository;
import com.afterduty.repository.ConditionRepository;
import com.afterduty.repository.EvidenceRepository;
import com.afterduty.repository.UserRepository;
import com.afterduty.service.extraction.ExtractionStateMachine;
import com.afterduty.service.gap.GapStateMachine;
import com.afterduty.service.synthesis.SynthesisStateMachine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * Polling scheduler that drives the evidence → synthesis → gap-analysis
 * pipeline automatically. Replaces the user-triggered "Analyze" button.
 *
 * State machine (per claim):
 *
 *   Extraction runs when extractionState != null and != COMPLETE.
 *   Synthesis runs when ALL:
 *     - Claim belongs to a user with an active subscription
 *       (or va-claim.subscription.free-analysis-tier=a1 — see below)
 *     - No evidence items are still being extracted (pending/processing)
 *     - The most recent atom was created at least {quietSeconds}s ago
 *     AND any of:
 *       - Never synthesized before
 *       - At least one atom was added after the last synthesis
 *       - synthesisState != null (in-flight state machine)
 *
 *   Gap analysis runs after synthesis completes when ALL:
 *     - Synthesis has completed at least once
 *     - Claim owner has an active subscription (the gap stage NEVER arms for
 *       free claims, in every flag mode; an already in-flight run is still
 *       driven to completion so a mid-run downgrade can't wedge the claim)
 *     AND any of:
 *       - Never ran gap analysis before
 *       - Last synthesis is newer than last gap run
 *       - gapState != null (in-flight state machine)
 *
 * Free-tier A1 (2026-07-01 review §6, Phase D): with
 * va-claim.subscription.free-analysis-tier=a1, FREE claims run extraction +
 * synthesis (capped by usage.free-limit-cents) and terminate cleanly at
 * synthesis-complete — markSynthesisComplete is terminal for them, so the
 * P0-4 progress fields clear and the claim lands in ANALYZED. Gap analysis,
 * what-if, chat, and re-run priority stay Pro. With the flag off (default),
 * free claims never advance at all — exactly the pre-Phase-D behavior.
 *
 * Triggers are deliberately data-driven only. The old "configured model
 * differs from last-run model" trigger compared the configured Claude model
 * id against a stored value that mostly came from Gemini-executed verify
 * jobs, so the strings could never match and every claim re-ran synthesis +
 * gap after each completion, forever. lastSynthesisModel/lastGapAnalysisModel
 * are still recorded for observability, but never drive re-runs.
 */
@Service
public class AnalysisScheduler {

    private static final Logger log = LoggerFactory.getLogger(AnalysisScheduler.class);

    private final ClaimRepository claimRepository;
    private final UserRepository userRepository;
    private final AtomRepository atomRepository;
    private final EvidenceRepository evidenceRepository;
    private final ConditionRepository conditionRepository;

    /**
     * The three state machines are injected as optional dependencies via setter
     * injection so that test slices that only import one state machine (e.g.
     * GapStateMachineTest imports GapStateMachine but not Synthesis/Extraction)
     * can wire AnalysisScheduler without a NoSuchBeanDefinitionException.
     * In production all three are always present.
     */
    @Nullable
    private SynthesisStateMachine synthesisStateMachine;

    @Nullable
    private GapStateMachine gapStateMachine;

    @Nullable
    private ExtractionStateMachine extractionStateMachine;

    /**
     * Mission 5b — no-new-facts short-circuit. Optional (setter-injected) so test
     * slices that don't import them still wire the scheduler. Used only by
     * {@link #shouldRunSynthesis} when the incremental flag is ON to detect a
     * re-extraction that produced zero NEW live facts (e.g. a duplicate-content
     * upload) and skip the synthesis re-run entirely.
     */
    @Nullable
    private com.afterduty.service.synthesis.ConditionGenerationService generationService;

    @Nullable
    private com.afterduty.config.LlmRoutingProperties routingProperties;

    /**
     * Item D (§6 A1) — free-tier boundary flag. Optional (setter-injected) so
     * test slices wire the scheduler without importing config; null ⇒ "off",
     * i.e. exactly the pre-Phase-D Pro-only behavior.
     */
    @Nullable
    private com.afterduty.config.SubscriptionProperties subscriptionProperties;

    /**
     * Reviewer-demo Pro allowlist (§H.7.2). Optional (setter-injected) so test
     * slices wire the scheduler without it; null ⇒ fall back to the stored
     * {@code hasActiveSubscription()} exactly as before.
     */
    @Nullable
    private com.afterduty.service.SubscriptionAccess subscriptionAccess;

    @Value("${va-claim.pipeline.quiet-seconds:30}")
    private int quietSeconds;

    @Value("${va-claim.analysis.incremental:true}")
    private boolean incrementalEnabled;

    private static final List<String> EXTRACTION_IN_FLIGHT =
            List.of("pending", "queued", "processing");

    public AnalysisScheduler(ClaimRepository claimRepository,
                             UserRepository userRepository,
                             AtomRepository atomRepository,
                             EvidenceRepository evidenceRepository,
                             ConditionRepository conditionRepository) {
        this.claimRepository = claimRepository;
        this.userRepository = userRepository;
        this.atomRepository = atomRepository;
        this.evidenceRepository = evidenceRepository;
        this.conditionRepository = conditionRepository;
    }

    @Autowired(required = false)
    @Lazy
    public void setSynthesisStateMachine(SynthesisStateMachine synthesisStateMachine) {
        this.synthesisStateMachine = synthesisStateMachine;
    }

    @Autowired(required = false)
    @Lazy
    public void setGapStateMachine(GapStateMachine gapStateMachine) {
        this.gapStateMachine = gapStateMachine;
    }

    @Autowired(required = false)
    @Lazy
    public void setExtractionStateMachine(ExtractionStateMachine extractionStateMachine) {
        this.extractionStateMachine = extractionStateMachine;
    }

    @Autowired(required = false)
    public void setGenerationService(
            com.afterduty.service.synthesis.ConditionGenerationService generationService) {
        this.generationService = generationService;
    }

    @Autowired(required = false)
    public void setRoutingProperties(com.afterduty.config.LlmRoutingProperties routingProperties) {
        this.routingProperties = routingProperties;
    }

    @Autowired(required = false)
    public void setSubscriptionProperties(
            com.afterduty.config.SubscriptionProperties subscriptionProperties) {
        this.subscriptionProperties = subscriptionProperties;
    }

    @Autowired(required = false)
    public void setSubscriptionAccess(
            com.afterduty.service.SubscriptionAccess subscriptionAccess) {
        this.subscriptionAccess = subscriptionAccess;
    }

    @Scheduled(fixedDelayString = "${va-claim.pipeline.poll-ms:15000}",
               initialDelayString = "${va-claim.pipeline.initial-delay-ms:20000}")
    public void tick() {
        List<Claim> claims;
        try {
            claims = claimRepository.findAll();
        } catch (Exception e) {
            log.warn("Scheduler tick skipped — DB error: {}", e.getMessage());
            return;
        }

        for (Claim c : claims) {
            try {
                advanceClaim(c);
            } catch (Exception e) {
                log.error("Scheduler failed on claim {}: {}", c.getId(), e.getMessage(), e);
            }
        }
    }

    private void advanceClaim(Claim claim) {
        // Item D (§6 A1) — the single all-or-nothing subscription gate becomes a
        // per-stage split. Flag OFF (default): free claims never advance at all,
        // exactly the pre-Phase-D behavior. Flag "a1": free claims run extraction
        // + synthesis (spend capped by usage.free-limit-cents via the same
        // upload-time UsageGuard deferral); the gap stage never ARMS for them.
        boolean subscribed = hasActiveSubscription(claim);
        if (!subscribed && !freeAnalysisA1()) return;

        // Drive extraction if in-flight
        if (extractionStateMachine != null
                && claim.getExtractionState() != null
                && !"COMPLETE".equals(claim.getExtractionState())) {
            extractionStateMachine.advance(claim);
            // do NOT return — synthesis/gap can tick in parallel
        }

        // Drive synthesis
        if (synthesisStateMachine != null && shouldRunSynthesis(claim)) {
            // Consume a re-analysis request as the run STARTS (state == null → about
            // to kick off a fresh run): clear synthesisNeeded so a completed run
            // can't re-trigger forever. A request arriving mid-run re-sets the flag
            // and is picked up on the next cycle after this run completes.
            if (claim.getSynthesisState() == null && Boolean.TRUE.equals(claim.getSynthesisNeeded())) {
                claim.setSynthesisNeeded(false);
                claimRepository.save(claim);
            }
            synthesisStateMachine.advance(claim);
        }

        // Drive gap analysis — Pro only. A free claim can never START a gap run
        // (gapState stays null so the "subscribed || in-flight" test below can
        // only pass for subscribers), but an already IN-FLIGHT run — a Pro user
        // whose subscription lapsed mid-run — is still driven to completion:
        // freezing it would wedge the claim with gapState set forever and the
        // spend for that run is already booked.
        if (gapStateMachine != null && shouldRunGapAnalysis(claim)
                && (subscribed || claim.getGapState() != null)) {
            gapStateMachine.advance(claim);
        }
    }

    /* --------- subscription gate --------- */

    private boolean hasActiveSubscription(Claim claim) {
        Optional<User> u = userRepository.findById(claim.getUserId());
        if (u.isEmpty()) return false;
        User user = u.get();
        // Reviewer-demo allowlist counts as Pro so the demo account's analysis
        // runs (§H.7.2); null in test slices ⇒ stored-subscription behavior.
        if (subscriptionAccess != null) return subscriptionAccess.isPro(user);
        return user.hasActiveSubscription();
    }

    /** Item D (§6 A1) — true when the free analysis tier is live. Null props ⇒ off. */
    private boolean freeAnalysisA1() {
        return subscriptionProperties != null && subscriptionProperties.isFreeAnalysisA1();
    }

    /* --------- synthesis eligibility --------- */

    private boolean shouldRunSynthesis(Claim claim) {
        // If synthesis state machine is in-flight, always tick
        if (claim.getSynthesisState() != null) return true;

        if (Boolean.TRUE.equals(claim.getSynthesisInProgress())) return false;

        // Need at least one LIVE atom to have something to synthesize. Mission 5a:
        // the trigger must look only at non-superseded atoms. If a re-extraction
        // superseded a doc's atoms, the replacement atoms are the ones that move
        // the latest-atom timestamp; counting superseded rows here could let a
        // pure-supersede transition (or a stale retired row) mis-fire the trigger.
        Instant latestAtom = atomRepository.findLatestCreatedAtByClaimIdAndSupersededByIsNull(claim.getId());
        if (latestAtom == null) return false;

        // Quiet period — no uploads or atom additions in the last N seconds.
        if (Instant.now().isBefore(latestAtom.plus(quietSeconds, ChronoUnit.SECONDS))) return false;

        // No extraction jobs still in flight.
        long inFlight = evidenceRepository.countByClaimIdAndProcessingStatusIn(claim.getId(), EXTRACTION_IN_FLIGHT);
        if (inFlight > 0) return false;

        // A re-analysis requested elsewhere (the Ask-AI chat's re-analysis action,
        // an intake correction) sets synthesisNeeded. Honor it as a first-class
        // trigger even when no NEW live atom moved the timestamp — otherwise the
        // flag is orphaned and a promised re-run never happens. It is consumed at
        // run START (see advanceClaim), so a completed run does NOT re-trigger every
        // tick; a request arriving mid-run re-sets it and is picked up next cycle.
        // Placed above the atom-timestamp + no-new-facts short-circuits so a
        // correction re-runs even with an unchanged corpus (the whole point).
        if (Boolean.TRUE.equals(claim.getSynthesisNeeded())) return true;

        // Trigger rules (data-driven only — see class javadoc).
        if (claim.getLastSynthesisAt() == null) return true;
        if (!latestAtom.isAfter(claim.getLastSynthesisAt())) return false;

        // Mission 5b — NO-NEW-FACTS short-circuit. The latest-atom timestamp moved
        // past the last synthesis (so the data-driven trigger above would fire),
        // but if extraction produced zero NEW live facts — e.g. a duplicate-content
        // re-upload that re-extracted to a byte-identical atom set — re-running the
        // whole synthesis pipeline (and paying for it) is pure waste. Detect this
        // by comparing the CURRENT live atom corpus's evidence fingerprint against
        // the fingerprint the last completed generation was built on (stamped on
        // every active condition). If they match, skip the re-run and stamp
        // lastSynthesisAt so the same unchanged atoms don't re-trigger next tick.
        // Flag OFF (or missing deps) ⇒ no short-circuit, today's behavior exactly.
        if (incrementalEnabled && generationService != null && hasNoNewFacts(claim)) {
            stampNoNewFactsSkip(claim.getId());
            log.info("[scheduler] claim {} — extraction produced no new live facts since last "
                    + "synthesis (duplicate-content upload); skipping synthesis re-run", claim.getId());
            return false;
        }
        return true;
    }

    /**
     * True when the current live atom corpus is evidence-identical to the corpus
     * the active generation was synthesized from. Compares the freshly computed
     * evidence fingerprint of the live atoms to the fingerprint stamped on the
     * active conditions. Returns false (⇒ run synthesis) whenever there is no
     * active generation to compare against, or its fingerprint is null/legacy, so
     * the short-circuit can only ever SKIP a provably redundant run, never
     * suppress a needed one.
     */
    private boolean hasNoNewFacts(Claim claim) {
        List<IdentifiedCondition> active =
                conditionRepository.findByClaimIdAndSupersededByIsNull(claim.getId());
        if (active.isEmpty()) return false; // no prior generation → must synthesize
        // Item B2 — with per-condition fingerprints ON, evidence_fingerprint is
        // scoped per condition and no longer comparable to a corpus hash; the run's
        // corpus-wide hash is stamped in corpus_fingerprint instead. Prefer it and
        // fall back to evidence_fingerprint for rows written before the column
        // existed (with scoping OFF the two columns hold the same value, so this
        // is byte-for-byte today's comparison).
        String priorFp = corpusLevelFingerprint(active.get(0));
        if (priorFp == null) return false; // legacy/uninitialized → re-synthesize once
        // All active conditions of one generation share the run's corpus
        // fingerprint; a mixed/inconsistent set means a partial/legacy state →
        // be conservative and re-synthesize.
        for (IdentifiedCondition c : active) {
            if (!priorFp.equals(corpusLevelFingerprint(c))) return false;
        }
        List<Atom> liveAtoms = atomRepository.findByClaimIdAndSupersededByIsNull(claim.getId());
        String currentFp = generationService.computeEvidenceFingerprint(liveAtoms, routedRateModelId());
        return priorFp.equals(currentFp);
    }

    /** The corpus-wide fingerprint a row was built on; legacy rows only have evidence_fingerprint. */
    @Nullable
    private static String corpusLevelFingerprint(IdentifiedCondition c) {
        return c.getCorpusFingerprint() != null ? c.getCorpusFingerprint() : c.getEvidenceFingerprint();
    }

    /**
     * Stamp lastSynthesisAt (and lastGapAnalysisAt) so a no-new-facts skip does
     * not re-trigger every tick. lastGapAnalysisAt is advanced too: nothing
     * changed, so a paid gap re-run on the unchanged conditions must not fire.
     * Its own (single-row) transaction via the repository save.
     */
    private void stampNoNewFactsSkip(Long claimId) {
        claimRepository.findById(claimId).ifPresent(c -> {
            Instant now = Instant.now();
            c.setLastSynthesisAt(now);
            if (c.getLastGapAnalysisAt() == null || c.getLastSynthesisAt().isAfter(c.getLastGapAnalysisAt())) {
                c.setLastGapAnalysisAt(now);
            }
            claimRepository.save(c);
        });
    }

    private String routedRateModelId() {
        if (routingProperties == null) return "default";
        com.afterduty.config.LlmRoutingProperties.PurposeRoute route =
                routingProperties.getPurposes().get("synthesis_rate");
        String model = route == null ? null : route.getModel();
        return model == null || model.isBlank() ? "default" : model.trim();
    }

    /* --------- gap-analysis eligibility --------- */

    private boolean shouldRunGapAnalysis(Claim claim) {
        // If gap state machine is in-flight, always tick
        if (claim.getGapState() != null) return true;

        if (Boolean.TRUE.equals(claim.getGapAnalysisInProgress())) return false;
        if (Boolean.TRUE.equals(claim.getSynthesisInProgress())) return false;
        if (claim.getLastSynthesisAt() == null) return false;

        if (claim.getLastGapAnalysisAt() == null) return true;
        if (claim.getLastSynthesisAt().isAfter(claim.getLastGapAnalysisAt())) return true;

        // Any active condition that was hand-edited or re-saved after the last gap
        // run? (Active generation only — Mission 5b.) IdentifiedCondition doesn't
        // track updatedAt; fall through to false.
        List<IdentifiedCondition> conditions =
                conditionRepository.findByClaimIdAndSupersededByIsNull(claim.getId());
        return false;
    }

    /* --------- completion callbacks (called by state machines) --------- */

    @Transactional
    public void markSynthesisComplete(Long claimId, String model) {
        claimRepository.findById(claimId).ifPresent(c -> {
            c.setSynthesisInProgress(false);
            c.setSynthesisState(null);
            c.setLastSynthesisAt(Instant.now());
            c.setLastSynthesisModel(model);
            clearErrorStatus(c);
            // Zero-conditions completion is TERMINAL: gap analysis has nothing
            // to run on, so no later callback would clear the progress fields.
            // With conditions present, the gap run's completion clears them.
            //
            // Item D (§6 A1): a FREE claim under free-analysis-tier=a1 is ALSO
            // terminal here — the gap stage never arms for it, so no later
            // callback would fire. finishAnalysis clears the P0-4 progress
            // fields and lands the claim in ANALYZED so conditions render and
            // Home never freezes mid-"analyzing". lastGapAnalysisAt is
            // deliberately NOT stamped: on upgrade to Pro the standard
            // lastSynthesisAt > lastGapAnalysisAt trigger arms the gap run the
            // new subscriber just paid for.
            if (conditionRepository.findByClaimIdAndSupersededByIsNull(claimId).isEmpty()
                    || (freeAnalysisA1() && !hasActiveSubscription(c))) {
                finishAnalysis(c, /*succeeded=*/true);
            }
            claimRepository.save(c);
        });
    }

    @Transactional
    public void markGapAnalysisComplete(Long claimId, String model) {
        claimRepository.findById(claimId).ifPresent(c -> {
            c.setGapAnalysisInProgress(false);
            c.setGapState(null);
            c.setLastGapAnalysisAt(Instant.now());
            c.setLastGapAnalysisModel(model);
            finishAnalysis(c, /*succeeded=*/true);
            claimRepository.save(c);
        });
    }

    /**
     * A successful run recovers the claim from a prior failure: without this,
     * status=ERROR + analysisMessage set by markSynthesisFailed/markGapAnalysisFailed
     * would stick forever even after a later run succeeds.
     */
    private void clearErrorStatus(Claim c) {
        if (c.getStatus() == Claim.ClaimStatus.ERROR) {
            c.setStatus(Claim.ClaimStatus.ANALYZED);
            c.setAnalysisMessage(null);
        }
    }

    /**
     * P0-4 — every terminal pipeline state funnels here. The analyzing-progress
     * fields are written exactly once, at upload (stage="extracting", pct=5);
     * the scheduler path historically never cleared them, so Home froze at 5%
     * forever on completion, failure, and zero-conditions completion alike.
     * On success the claim also lands in ANALYZED with lastAnalyzedAt stamped,
     * so clients can tell "analysis finished, zero conditions" apart from
     * "never analyzed". On failure the caller's status=ERROR + analysisMessage
     * stand — only the stale progress fields are cleared.
     */
    private void finishAnalysis(Claim c, boolean succeeded) {
        c.setAnalysisStage(null);
        c.setAnalysisProgressPct(null);
        if (succeeded) {
            c.setStatus(Claim.ClaimStatus.ANALYZED);
            c.setAnalysisMessage(null);
            c.setLastAnalyzedAt(Instant.now());
        }
    }

    /**
     * Terminal failure for a synthesis run. Mirrors markSynthesisComplete so the
     * claim is never wedged mid-pipeline: the state column is cleared, the
     * in-progress flag drops, and the error is surfaced via the claim's
     * existing failure representation (status=ERROR + analysisMessage — the
     * same shape PipelineService uses). Stamping lastSynthesisAt means the
     * failed run does NOT immediately re-trigger; the next new atom (upload or
     * chat) re-runs synthesis naturally via the data-driven trigger.
     * lastGapAnalysisAt is stamped too: a failed synthesis produced no new
     * conditions, so it must not look like fresh synthesis output to
     * shouldRunGapAnalysis (which would burn a paid gap run on stale data).
     */
    @Transactional
    public void markSynthesisFailed(Long claimId, String pipelineError) {
        claimRepository.findById(claimId).ifPresent(c -> {
            c.setSynthesisInProgress(false);
            c.setSynthesisState(null);
            c.setLastSynthesisAt(Instant.now());
            c.setLastGapAnalysisAt(Instant.now());
            finishAnalysis(c, /*succeeded=*/false);
            c.setStatus(Claim.ClaimStatus.ERROR);
            c.setAnalysisMessage(pipelineError);
            claimRepository.save(c);
        });
    }

    /** Terminal failure for a gap-analysis run; see {@link #markSynthesisFailed}. */
    @Transactional
    public void markGapAnalysisFailed(Long claimId, String pipelineError) {
        claimRepository.findById(claimId).ifPresent(c -> {
            c.setGapAnalysisInProgress(false);
            c.setGapState(null);
            c.setLastGapAnalysisAt(Instant.now());
            finishAnalysis(c, /*succeeded=*/false);
            c.setStatus(Claim.ClaimStatus.ERROR);
            c.setAnalysisMessage(pipelineError);
            claimRepository.save(c);
        });
    }
}
