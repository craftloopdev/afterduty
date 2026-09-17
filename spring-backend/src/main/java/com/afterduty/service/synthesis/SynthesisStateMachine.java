package com.afterduty.service.synthesis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.afterduty.model.Atom;
import com.afterduty.model.Claim;
import com.afterduty.model.ClaimPipelineJob;
import com.afterduty.model.IdentifiedCondition;
import com.afterduty.repository.AtomRepository;
import com.afterduty.repository.ClaimPipelineJobRepository;
import com.afterduty.repository.ClaimRepository;
import com.afterduty.repository.IdentifiedConditionRepository;
import com.afterduty.repository.LlmJobRepository;
import com.afterduty.service.AnalysisScheduler;
import com.afterduty.service.VaMathService;
import com.afterduty.service.llm.LlmJobRequest;
import com.afterduty.service.llm.LlmJobResult;
import com.afterduty.service.llm.LlmJobService;
import com.afterduty.config.LlmRoutingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Hand-rolled state machine driving the synthesis pipeline.
 * State stored in Claim.synthesisState (string-enum). Advances at most one
 * transition per call to advance(). AnalysisScheduler.tick() drives it.
 */
@Service
public class SynthesisStateMachine {

    private static final Logger log = LoggerFactory.getLogger(SynthesisStateMachine.class);

    public enum State { NONE, IDENTIFYING, MERGING, RATING, VERIFYING, COMPLETE }

    private final ClaimRepository claimRepository;
    private final IdentifiedConditionRepository conditionRepository;
    private final AtomRepository atomRepository;
    private final ClaimPipelineJobRepository pipelineJobRepository;
    private final LlmJobService llmJobService;
    private final LlmJobRepository llmJobRepository;
    private final ConditionIdentificationAgent identificationAgent;
    private final DuplicateConditionMerger duplicateConditionMerger;
    private final RatingAgent ratingAgent;
    private final SynthesisVerificationAgent verificationAgent;
    private final EnhancedSynthesisOrchestrator coordinator;
    private final AnalysisScheduler analysisScheduler;
    private final VaMathService vaMathService;
    private final ConditionGenerationService generationService;
    private final com.afterduty.service.DomainCorrectionsService domainCorrectionsService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Mission 5b — gate condition generations, carry-forward, and atomic
     * supersede behind the same flag as Mission 5a's extraction delta. Flag OFF
     * restores today's exact semantics: every condition is rated every run, prior
     * conditions are appended (never superseded), and no fingerprints are written.
     * Rollback = INCREMENTAL_ANALYSIS=false (no code deploy).
     */
    @Value("${va-claim.analysis.incremental:true}")
    private boolean incrementalEnabled;

    /**
     * Increment B — provisional PACT/presumptive inference from evidence atoms.
     * When true (owner wants it live), after all conditions are mapped for a run
     * the coordinator reconciles each against the deterministic PACT-Act rules
     * engine using signals DERIVED FROM EVIDENCE ATOMS (Iraq/Afghanistan
     * deployment, burn-pit exposure, etc.) merged with any stored ServiceProfile.
     * On a match it flips a PROVISIONAL presumptive (labeled "pending your
     * confirmation") and rewrites the stale nexus triad so the gap analyzer stops
     * recommending a nexus letter. Legal-weight, so gated: set
     * PRESUMPTIVE_ATOM_INFERENCE=false to fully disable (no code deploy) — the
     * pipeline then reverts to today's exact behavior (is_presumptive from the
     * identify LLM only).
     */
    @Value("${va-claim.analysis.presumptive-atom-inference:true}")
    private boolean presumptiveAtomInferenceEnabled;

    /**
     * Secondary-aware analysis (38 CFR 3.310). When true (default), after every
     * condition for a run is mapped and persisted the coordinator reconciles each
     * SECONDARY condition (one whose identify-supplied {@code secondaryTo} is set):
     * it REWRITES the "in-service" triad leg to represent the load-bearing question
     * — is the PRIMARY service-connected? — instead of a fabricated in-service
     * event, resolving the primary's SC status from evidence atoms (KNOWN) / an
     * in-claim strong primary (LIKELY) / neither (UNKNOWN → ask the veteran), and
     * appends a transitive-dependency note to the secondary's rating rationale. It
     * never asserts the primary IS service-connected unless an atom says so, and it
     * does NOT change the rating number. Runs BEFORE the rate fan-out so the
     * corrected in-service leg flows into the rate + gap prompts. OFF
     * (SECONDARY_AWARE_ANALYSIS=false, no code deploy) reverts to today's exact
     * behavior (the direct-service-connection triad the identify LLM produced).
     */
    @Value("${va-claim.analysis.secondary-aware:true}")
    private boolean secondaryAwareEnabled;

    /**
     * Rating honesty (owner-approved). When true (default), after each condition's
     * rating is persisted the coordinator runs a deterministic post-pass
     * ({@code assessRatingEvidence}): for every condition whose VASRD code is in the
     * curated objective-evidence map, if the measure its rating tiers hinge on (a PFT
     * for asthma/COPD, an audiogram for hearing loss, …) is ABSENT from the evidence it
     * (a) sets {@code ratingEvidenceNote} = "Estimate — needs &lt;measure&gt; to
     * confirm…" and (b) tempers the SERVED confidence to reflect evidence completeness
     * rather than the model's self-report — so an unsupported rating can't show 90%+.
     * The estimated rating NUMBER is never changed, and UNMAPPED codes are untouched.
     * Runs after the rate fan-out but before verification, so the tempered confidence +
     * note persist and flow forward. OFF (RATING_HONESTY=false, no code deploy) reverts
     * to today's exact behavior — the LLM's self-reported confidence, no note.
     */
    @Value("${va-claim.analysis.rating-honesty:true}")
    private boolean ratingHonestyEnabled;

    /**
     * Pyramiding grouped view (owner-approved). When true (default), after every
     * condition's rating is persisted the coordinator runs a deterministic post-pass
     * ({@code assignPyramidingGroups}): each condition whose VASRD code maps to a
     * canonical pyramiding group ({@code PyramidingGroups}, e.g. all §4.130
     * mental-health codes) gets that group set on {@code pyramidGroup}; within a group
     * the highest-rated member is marked {@code pyramidPrimary} (the one PyramidingRules
     * keeps in the combined math) and the others get a plain-language {@code pyramidReason}
     * ("rated together … the strongest counts, they don't add") — the SAME rows the math
     * layer then excludes, so grouping + math stay consistent and nothing is double-
     * counted. Every member is stamped with the group's effective rating. TBI (8045) is
     * never merged; it gets an advisory overlap note only. The rating NUMBER is never
     * changed. Runs after ratings persist (rating-honesty pass), before verify, so the
     * deterministic group/reason persist and flow forward, REPLACING the unreliable
     * LLM-set pyramidGroup. OFF (PYRAMIDING_GROUPS=false, no code deploy) reverts to
     * today's behavior — whatever pyramidGroup/pyramidReason the LLM/verify set.
     */
    @Value("${va-claim.analysis.pyramiding-groups:true}")
    private boolean pyramidingGroupsEnabled;

    /**
     * Increment E — deterministic-first rating (VasrdDecisionEngine). DEFAULT OFF,
     * pending eval validation. When true, the rate fan-out attempts a deterministic
     * 38 CFR rating for each DIRTY condition whose VASRD code the engine covers,
     * BEFORE submitting the LLM rate job: if the condition's live evidence atoms
     * actually satisfy a criteria match (a real FEV-1 %, a documented CPAP, ≥3
     * corticosteroid courses, a flexion-degrees ROM, …), the engine's rating +
     * 38-CFR-cited rationale + evidence-grounded confidence is PERSISTED and NO LLM
     * rate job is submitted for that condition. If the engine returns empty (criteria
     * unmet from the atoms) OR the code is uncovered, the condition FALLS THROUGH to
     * the existing LLM rate path unchanged. OFF (default) ⇒ today's exact behavior:
     * no deterministic call is made and every condition is rated by the LLM. Rollback =
     * RATING_DETERMINISTIC_ENGINE=false (no code deploy).
     */
    @Value("${va-claim.analysis.deterministic-rating:false}")
    private boolean deterministicRatingEnabled;

    /**
     * Optional (setter-injected) so test slices that don't import the routing
     * config still wire. Used only to stamp the routed rate model into the
     * evidence fingerprint so a model swap invalidates carry-forward. Absent ⇒ a
     * stable placeholder token (deterministic within a run).
     */
    @Nullable
    private LlmRoutingProperties routingProperties;

    /**
     * Increment E — the deterministic VASRD decision engine. Setter-injected and
     * OPTIONAL (mirrors {@link #routingProperties}) so the many existing synthesis
     * test slices that do not import {@link VasrdDecisionEngine} keep wiring unchanged.
     * It is consulted ONLY when {@link #deterministicRatingEnabled} is true; with the
     * flag off (the default) a null engine is never touched, so absence is safe and
     * behavior is byte-identical to today.
     */
    @Nullable
    private VasrdDecisionEngine vasrdDecisionEngine;

    public SynthesisStateMachine(ClaimRepository claimRepository,
                                  IdentifiedConditionRepository conditionRepository,
                                  AtomRepository atomRepository,
                                  ClaimPipelineJobRepository pipelineJobRepository,
                                  LlmJobService llmJobService,
                                  LlmJobRepository llmJobRepository,
                                  ConditionIdentificationAgent identificationAgent,
                                  DuplicateConditionMerger duplicateConditionMerger,
                                  RatingAgent ratingAgent,
                                  SynthesisVerificationAgent verificationAgent,
                                  EnhancedSynthesisOrchestrator coordinator,
                                  AnalysisScheduler analysisScheduler,
                                  VaMathService vaMathService,
                                  ConditionGenerationService generationService,
                                  com.afterduty.service.DomainCorrectionsService domainCorrectionsService) {
        this.claimRepository = claimRepository;
        this.conditionRepository = conditionRepository;
        this.atomRepository = atomRepository;
        this.pipelineJobRepository = pipelineJobRepository;
        this.llmJobService = llmJobService;
        this.llmJobRepository = llmJobRepository;
        this.identificationAgent = identificationAgent;
        this.duplicateConditionMerger = duplicateConditionMerger;
        this.ratingAgent = ratingAgent;
        this.verificationAgent = verificationAgent;
        this.coordinator = coordinator;
        this.analysisScheduler = analysisScheduler;
        this.vaMathService = vaMathService;
        this.generationService = generationService;
        this.domainCorrectionsService = domainCorrectionsService;
    }

    @Autowired(required = false)
    public void setRoutingProperties(LlmRoutingProperties routingProperties) {
        this.routingProperties = routingProperties;
    }

    @Autowired(required = false)
    public void setVasrdDecisionEngine(VasrdDecisionEngine vasrdDecisionEngine) {
        this.vasrdDecisionEngine = vasrdDecisionEngine;
    }

    private String routedRateModelId() {
        if (routingProperties == null) return "default";
        LlmRoutingProperties.PurposeRoute route = routingProperties.getPurposes().get("synthesis_rate");
        String model = route == null ? null : route.getModel();
        return model == null || model.isBlank() ? "default" : model.trim();
    }

    @Transactional
    public void advance(Claim claim) {
        State current = parseState(claim.getSynthesisState());
        switch (current) {
            case NONE       -> doIdentify(claim);
            case IDENTIFYING -> doMergeIfReady(claim);
            case MERGING    -> doRateIfReady(claim);
            case RATING     -> doVerifyIfReady(claim);
            case VERIFYING  -> doCompleteIfReady(claim);
            case COMPLETE   -> { /* no-op */ }
        }
    }

    // -------------------------------------------------------------------------
    // NONE → IDENTIFYING
    // -------------------------------------------------------------------------

    private void doIdentify(Claim claim) {
        // Mission 5a: synthesis must reason over LIVE atoms only. After a doc is
        // re-extracted its prior atoms are marked superseded (kept for citation
        // history), so the full findByClaimId would feed the model both the stale
        // and the fresh copy of the same evidence — inflating/contradicting facts.
        List<Atom> atoms = atomRepository.findByClaimIdAndSupersededByIsNull(claim.getId());
        String serviceContext = coordinator.buildServiceContext(claim.getUserId());
        String presumptiveContext = coordinator.buildPresumptiveContext(claim.getUserId());

        LlmJobRequest req = identificationAgent.buildRequest(atoms, serviceContext, presumptiveContext,
                claim.getId(), claim.getUserId());

        // Clear stale rows from prior runs so the IfReady readers only ever see
        // this run's jobs (stage readers take pjobs.get(0) of an unordered list).
        pipelineJobRepository.deleteByClaimIdAndStage(claim.getId(), "synthesis_identify");
        UUID jobId = llmJobService.submit(req);

        pipelineJobRepository.save(new ClaimPipelineJob(
                claim.getId(), "synthesis_identify", jobId, null, null));

        claim.setSynthesisState(State.IDENTIFYING.name());
        claim.setSynthesisInProgress(true);
        claimRepository.save(claim);
        log.info("[synthesis] NONE → IDENTIFYING for claim {}", claim.getId());
    }

    // -------------------------------------------------------------------------
    // IDENTIFYING → MERGING (or COMPLETE if empty)
    // -------------------------------------------------------------------------

    private void doMergeIfReady(Claim claim) {
        List<ClaimPipelineJob> pjobs = pipelineJobRepository
                .findByClaimIdAndStage(claim.getId(), "synthesis_identify");
        if (pjobs.isEmpty()) return;

        Set<UUID> jobIds = collectJobIds(pjobs);
        if (!llmJobService.allTerminal(jobIds)) return;
        if (!llmJobService.allSucceeded(jobIds)) {
            failSynthesis(claim, "synthesis_identify", jobIds);
            return;
        }

        // Parse identify result
        UUID identifyJobId = pjobs.get(0).getLlmJobId();
        LlmJobResult identifyResult = llmJobService.getResult(identifyJobId)
                .orElseThrow(() -> new IllegalStateException("identify job not SUCCEEDED"));

        List<Map<String, Object>> conditions = identificationAgent.parseResponse(identifyResult);

        if (conditions == null) {
            // Unparseable model response. Telling a veteran with records "no
            // conditions found" because of a parse failure would be a silent
            // wrong answer, so fail the run instead of completing with zero.
            boolean truncated = identificationAgent.wasTruncated(identifyResult);
            log.error("[synthesis] identify response {} for claim {} — marking FAILED",
                    truncated ? "TRUNCATED at the output budget" : "unparseable", claim.getId());
            analysisScheduler.markSynthesisFailed(claim.getId(), truncated
                    ? "identify_truncated: the evidence summary was too large to analyze in one pass; "
                            + "it will retry when new evidence is added"
                    : "identify_unparseable: analysis could not read the evidence summary; "
                            + "it will retry when new evidence is added");
            return;
        }

        if (conditions.isEmpty()) {
            // A successfully parsed empty array is a legitimate answer — the
            // evidence on file (or lack of it) genuinely surfaced no claimable
            // conditions (e.g. non-medical uploads).
            //
            // Mission 5b — on a RE-RUN this empty result must retire the prior
            // generation, else the veteran would keep seeing conditions the
            // current evidence no longer supports (the "never stale" contract).
            // There is no new generation to point at, so every prior active row is
            // tombstoned. With the flag OFF this is a no-op (no supersede) and the
            // legacy fast-path is unchanged.
            if (incrementalEnabled) {
                List<IdentifiedCondition> priorGeneration =
                        conditionRepository.findByClaimIdAndSupersededByIsNull(claim.getId());
                generationService.supersedePriorGeneration(priorGeneration, List.of());
            }
            log.info("[synthesis] identify legitimately found no conditions for claim {} — fast-pathing to COMPLETE",
                    claim.getId());
            analysisScheduler.markSynthesisComplete(claim.getId(), "unknown");
            return;
        }

        LlmJobRequest mergeReq = duplicateConditionMerger.buildRequest(conditions, claim.getId(), claim.getUserId());
        pipelineJobRepository.deleteByClaimIdAndStage(claim.getId(), "synthesis_duplicate_merger");
        UUID mergeJobId = llmJobService.submit(mergeReq);
        pipelineJobRepository.save(new ClaimPipelineJob(
                claim.getId(), "synthesis_duplicate_merger", mergeJobId, null, null));

        claim.setSynthesisState(State.MERGING.name());
        claimRepository.save(claim);
        log.info("[synthesis] IDENTIFYING → MERGING for claim {}", claim.getId());
    }

    // -------------------------------------------------------------------------
    // MERGING → RATING
    // -------------------------------------------------------------------------

    private void doRateIfReady(Claim claim) {
        List<ClaimPipelineJob> pjobs = pipelineJobRepository
                .findByClaimIdAndStage(claim.getId(), "synthesis_duplicate_merger");
        if (pjobs.isEmpty()) return;

        Set<UUID> jobIds = collectJobIds(pjobs);
        if (!llmJobService.allTerminal(jobIds)) return;
        if (!llmJobService.allSucceeded(jobIds)) {
            failSynthesis(claim, "synthesis_duplicate_merger", jobIds);
            return;
        }

        // Fetch identify result (stored in identifying stage)
        List<ClaimPipelineJob> identifyPjobs = pipelineJobRepository
                .findByClaimIdAndStage(claim.getId(), "synthesis_identify");
        LlmJobResult identifyResult = llmJobService.getResult(identifyPjobs.get(0).getLlmJobId())
                .orElseThrow();
        // Identify parsed successfully in doMergeIfReady or we wouldn't be in
        // MERGING; a null here can only mean stale/corrupt rows — fall back to
        // an empty list so the merger keeps the merge result authoritative.
        List<Map<String, Object>> originalConditions = identificationAgent.parseResponse(identifyResult);
        if (originalConditions == null) originalConditions = List.of();

        // Fetch merge result
        UUID mergeJobId = pjobs.get(0).getLlmJobId();
        LlmJobResult mergeResult = llmJobService.getResult(mergeJobId).orElseThrow();
        List<Map<String, Object>> mergedConditions = duplicateConditionMerger.parseResponse(mergeResult, originalConditions);

        // Mission 5a: synthesis must reason over LIVE atoms only. After a doc is
        // re-extracted its prior atoms are marked superseded (kept for citation
        // history), so the full findByClaimId would feed the model both the stale
        // and the fresh copy of the same evidence — inflating/contradicting facts.
        List<Atom> atoms = atomRepository.findByClaimIdAndSupersededByIsNull(claim.getId());

        // Mission 5b — generation carry-forward. The evidence fingerprint is shared
        // across this run's conditions (the rating prompt sees the whole live atom
        // corpus), so compute it once. The prior generation = the currently-active
        // conditions; snapshot them BEFORE persisting the new generation. With the
        // flag OFF both are empty/no-ops and the loop below behaves exactly as before.
        String runEvidenceFp = incrementalEnabled
                ? generationService.computeEvidenceFingerprint(atoms, routedRateModelId())
                : null;
        List<IdentifiedCondition> priorGeneration = incrementalEnabled
                ? conditionRepository.findByClaimIdAndSupersededByIsNull(claim.getId())
                : List.of();
        Map<String, IdentifiedCondition> priorByIdentity = incrementalEnabled
                ? generationService.indexPriorByIdentity(priorGeneration)
                : Map.of();

        // Persist the new generation. With the flag ON each row is written PENDING
        // (superseded_by = PENDING_MARKER) so external readers keep seeing the
        // prior generation until COMPLETE activates this one atomically. Fingerprints
        // are stamped here so classify() can compare against the prior generation.
        //
        // Item B2 — attribution at identify: the identify response (prompt v2) cites
        // supporting_atom_ids per condition; the index recovers the union across
        // merged duplicates (keyed by identity fingerprint) from the PRE-merge maps.
        // applyAttribution persists the ids (flag-independent, pure data);
        // stampEvidenceFingerprints writes corpus_fingerprint always and scopes
        // evidence_fingerprint to the attributed atoms only behind
        // va-claim.synthesis.per-condition-fingerprint (default OFF — with the flag
        // off it stamps exactly runEvidenceFp, today's behavior byte-for-byte).
        Map<String, Set<Long>> attributionIndex = incrementalEnabled
                ? generationService.buildAttributionIndex(originalConditions)
                : Map.of();
        List<IdentifiedCondition> savedConditions = new ArrayList<>();
        for (Map<String, Object> condMap : mergedConditions) {
            IdentifiedCondition cond = coordinator.mapToCondition(condMap, claim.getId());
            if (incrementalEnabled) {
                cond.setIdentityFingerprint(generationService.computeIdentityFingerprint(cond));
                generationService.applyAttribution(cond, condMap, attributionIndex, atoms);
                generationService.stampEvidenceFingerprints(cond, atoms, routedRateModelId(), runEvidenceFp);
                cond.setSupersededBy(ConditionGenerationService.PENDING_MARKER);
            }
            savedConditions.add(conditionRepository.save(cond));
        }

        // Increment B — provisional presumptive reconciliation. Now that every
        // condition for this generation is mapped and persisted, reconcile them
        // against the deterministic PACT-Act rules engine using signals DERIVED
        // FROM EVIDENCE ATOMS (merged with any stored ServiceProfile). A match
        // flips a PROVISIONAL presumptive (clearly labeled "pending confirmation")
        // and rewrites the stale nexus triad so the downstream gap analyzer stops
        // asking for a nexus letter. Runs BEFORE the rate fan-out so the corrected
        // is_presumptive / presumptive_basis / nexus flow into the rate + gap
        // prompts. Gated by presumptive-atom-inference (default ON); OFF reverts to
        // today's exact behavior (is_presumptive from the identify LLM only).
        if (presumptiveAtomInferenceEnabled) {
            int flipped = coordinator.reconcilePresumptiveFromEvidence(
                    savedConditions, claim.getId(), claim.getUserId());
            if (flipped > 0) {
                for (IdentifiedCondition cond : savedConditions) {
                    conditionRepository.save(cond);
                }
                log.info("[synthesis] provisional presumptive reconciliation flipped {} condition(s) for claim {}",
                        flipped, claim.getId());
            }
        }

        // Self-correction KB (domain-corrections.json) — the deterministic
        // guarantee that a feedback-disproven claim never reaches a veteran
        // again, no matter which lane re-asserted it (identify LLM or the
        // rules engine above). Runs after both presumptive lanes and before
        // the rate/gap fan-out so the repaired triad flows into those prompts.
        // (reconcileSecondary below touches only triadInService — no overlap.)
        int corrected = domainCorrectionsService.enforce(savedConditions);
        if (corrected > 0) {
            for (IdentifiedCondition cond : savedConditions) {
                conditionRepository.save(cond);
            }
            log.info("[synthesis] domain corrections repaired {} condition(s) for claim {}",
                    corrected, claim.getId());
        }

        // Secondary-aware reconciliation (38 CFR 3.310). For each condition the
        // identify LLM marked secondary (secondaryTo set), rewrite its "in-service"
        // triad leg to represent the PRIMARY-is-service-connected requirement (not a
        // fabricated in-service event) and append a transitive-dependency note. The
        // primary's SC status is resolved deterministically from evidence atoms
        // (KNOWN) / an in-claim strong primary (LIKELY) / neither (UNKNOWN → ask the
        // veteran via the gap layer). Runs BEFORE the rate fan-out so the corrected
        // in-service leg flows into the rate + gap prompts, right after the
        // presumptive pass (they are independent — a condition can be neither, one,
        // or in principle both). Gated by secondary-aware (default ON); OFF reverts
        // to the direct-service-connection triad the identify LLM produced.
        if (secondaryAwareEnabled) {
            int reframed = coordinator.reconcileSecondary(savedConditions, claim.getId());
            if (reframed > 0) {
                for (IdentifiedCondition cond : savedConditions) {
                    conditionRepository.save(cond);
                }
                log.info("[synthesis] secondary-aware reconciliation reframed {} condition(s) for claim {}",
                        reframed, claim.getId());
            }
        }

        // Fan out rate jobs (clearing any prior run's rows first). With the flag ON,
        // a CLEAN condition (identity AND evidence both match a prior active
        // condition) carries its rating/verification/gap outputs forward and
        // submits NO rate job; only DIRTY conditions fan out. With the flag OFF
        // every condition is rated, exactly as before.
        pipelineJobRepository.deleteByClaimIdAndStage(claim.getId(), "synthesis_rate");
        List<ClaimPipelineJob> ratePjobs = new ArrayList<>();
        int clean = 0;
        int dirty = 0;
        int deterministic = 0;
        for (IdentifiedCondition cond : savedConditions) {
            ConditionGenerationService.CarryDecision decision = incrementalEnabled
                    ? generationService.classify(cond, priorByIdentity)
                    : ConditionGenerationService.CarryDecision.dirty();
            if (decision.clean()) {
                generationService.carryForward(cond, decision.priorMatch());
                conditionRepository.save(cond);
                clean++;
                continue;
            }
            // Increment E — deterministic-first, LLM fallback (DEFAULT OFF). Before
            // fanning out the LLM rate job for this DIRTY condition, if the flag is on
            // AND the condition's VASRD code is one VasrdDecisionEngine covers, try a
            // deterministic 38 CFR rating from the LIVE evidence atoms. On a CONFIDENT
            // match (the atoms genuinely satisfy a criteria tier — a real FEV-1 %, a
            // documented CPAP, ≥3 corticosteroid courses, a flexion-degrees ROM, …) the
            // engine's rating + cited rationale + evidence-grounded confidence is
            // PERSISTED here and NO LLM rate job is submitted for it. If the engine
            // returns empty (criteria unmet from the atoms) or the code is uncovered it
            // falls through to the LLM path below, unchanged. Flag OFF ⇒ this whole block
            // is skipped and the condition is rated by the LLM exactly as today.
            if (deterministicRatingEnabled
                    && applyDeterministicRatingIfConfident(cond, atoms)) {
                conditionRepository.save(cond);
                deterministic++;
                continue;
            }
            LlmJobRequest rateReq = ratingAgent.buildRequest(cond, atoms, claim.getId(), claim.getUserId());
            UUID rateJobId = llmJobService.submit(rateReq);
            ratePjobs.add(new ClaimPipelineJob(
                    claim.getId(), "synthesis_rate", rateJobId, cond.getId(), null));
            dirty++;
        }
        pipelineJobRepository.saveAll(ratePjobs);

        claim.setSynthesisState(State.RATING.name());
        claimRepository.save(claim);
        if (incrementalEnabled) {
            log.info("[synthesis] MERGING → RATING for claim {}, {} conditions ({} dirty re-rated by LLM, "
                    + "{} deterministically rated, {} clean carried forward)",
                    claim.getId(), savedConditions.size(), dirty, deterministic, clean);
        } else {
            log.info("[synthesis] MERGING → RATING for claim {}, {} conditions ({} LLM-rated, "
                    + "{} deterministically rated)",
                    claim.getId(), savedConditions.size(), dirty, deterministic);
        }
    }

    /**
     * Increment E — attempt a CONFIDENT deterministic rating for a DIRTY condition and,
     * on success, write it onto {@code cond} in place (rating number, 38-CFR rationale,
     * evidence-grounded confidence). Returns true iff the deterministic engine produced a
     * rating (⇒ the caller must persist {@code cond} and skip the LLM rate job); false ⇒
     * the caller falls through to the LLM path unchanged.
     *
     * <p>The "confident deterministic rating" predicate is exactly
     * {@link VasrdDecisionEngine#tryDeterministicRating}'s non-empty return. That engine
     * is criteria-gated by construction: it returns a rating ONLY when the atoms actually
     * satisfy a 38 CFR tier (a parsed FEV-1 % for asthma, a documented CPAP / respiratory
     * failure / hypersomnolence for sleep apnea, a flexion-degrees ROM for the knee, a
     * prostrating-attack description for migraines, ≥1 diagnosis atom for the fixed-rule
     * codes) and returns {@code Optional.empty()} when the code is uncovered, no atoms are
     * present, or the atoms carry no criteria-supporting value. So a non-quantitative /
     * uncovered code and a covered code whose atoms lack the deciding measure BOTH fall
     * through to the LLM — the engine never guesses. The confidence it returns
     * (0.88–0.99) is grounded in the specific atom evidence it matched, not an LLM
     * self-report. This method NEVER runs with the flag off (the caller gates it) and is
     * null-safe if the optional engine bean is absent.
     */
    private boolean applyDeterministicRatingIfConfident(IdentifiedCondition cond, List<Atom> atoms) {
        if (vasrdDecisionEngine == null) {
            return false;
        }
        Optional<Map<String, Object>> det =
                vasrdDecisionEngine.tryDeterministicRating(cond.getVasrdCode(), atoms);
        if (det.isEmpty()) {
            return false;
        }
        Map<String, Object> rating = det.get();
        Object estimated = rating.get("estimated_rating");
        Object rationale = rating.get("rating_rationale");
        Object confidence = rating.get("confidence");
        if (!(estimated instanceof Number)) {
            // Defensive: an engine result missing its rating number is not usable —
            // fall through to the LLM rather than persist a null rating.
            return false;
        }
        cond.setEstimatedRating(((Number) estimated).intValue());
        if (rationale instanceof String s) {
            cond.setRatingRationale(s);
        }
        if (confidence instanceof Number n) {
            cond.setConfidence(n.doubleValue());
        }
        log.info("[synthesis] deterministic rating applied for claim {} condition {} (DC {}) → {}%",
                cond.getClaimId(), cond.getId(), cond.getVasrdCode(), cond.getEstimatedRating());
        return true;
    }

    // -------------------------------------------------------------------------
    // RATING → VERIFYING
    // -------------------------------------------------------------------------

    private void doVerifyIfReady(Claim claim) {
        List<ClaimPipelineJob> ratePjobs = pipelineJobRepository
                .findByClaimIdAndStage(claim.getId(), "synthesis_rate");
        if (ratePjobs.isEmpty()) {
            // Mission 5b — all-clean carry-forward: every condition matched a prior
            // active condition (identity + evidence), so ZERO rate jobs were
            // submitted. That's the headline no-LLM win, but it must not wedge the
            // run in RATING. Proceed straight to building verification over the
            // (carried-forward) pending generation. The legacy guard (return on
            // empty) is preserved when the flag is off, or when there are no
            // pending conditions (a genuinely empty/odd state).
            boolean allClean = incrementalEnabled && !currentRunConditions(claim).isEmpty();
            if (!allClean) return;
        } else {
            Set<UUID> jobIds = collectJobIds(ratePjobs);
            if (!llmJobService.allTerminal(jobIds)) return;
            if (!llmJobService.allSucceeded(jobIds)) {
                failSynthesis(claim, "synthesis_rate", jobIds);
                return;
            }
        }

        // Update each condition with its rating
        for (ClaimPipelineJob rpj : ratePjobs) {
            LlmJobResult rateResult = llmJobService.getResult(rpj.getLlmJobId()).orElseThrow();
            Map<String, Object> ratingMap = ratingAgent.parseResponse(rateResult);

            if (rpj.getConditionId() != null) {
                conditionRepository.findById(rpj.getConditionId()).ifPresent(cond -> {
                    Object rating = ratingMap.get("estimated_rating");
                    Object rationale = ratingMap.get("rating_rationale");
                    Object confidence = ratingMap.get("confidence");
                    if (rating instanceof Number n) cond.setEstimatedRating(n.intValue());
                    if (rationale instanceof String s) cond.setRatingRationale(s);
                    if (confidence instanceof Number n) cond.setConfidence(n.doubleValue());
                    conditionRepository.save(cond);
                });
            }
        }

        // Build conditions list for verification — the in-run (pending) generation
        // when incremental is on, so verification reasons over exactly the new
        // generation and never the still-active prior one.
        List<IdentifiedCondition> conditions = currentRunConditions(claim);

        // Rating honesty (owner-approved). Now that every condition's rating +
        // self-reported confidence is persisted, run the deterministic objective-
        // evidence post-pass: tag conditions whose code's required measure (PFT,
        // audiogram, …) is absent from the evidence and temper the served confidence
        // toward evidence completeness (the LLM self-report can't stand as-is over
        // missing objective data). Never changes the rating number; unmapped codes
        // untouched. Runs here — after ratings persist, before verify — so the note +
        // tempered confidence persist and flow into the verify/gap prompts. Gated by
        // rating-honesty (default ON); OFF reverts to the LLM's raw confidence, no note.
        if (ratingHonestyEnabled) {
            int assessed = coordinator.assessRatingEvidence(conditions, claim.getId());
            if (assessed > 0) {
                for (IdentifiedCondition cond : conditions) {
                    conditionRepository.save(cond);
                }
                log.info("[synthesis] rating-honesty assessed {} condition(s) for claim {}",
                        assessed, claim.getId());
            }
        }

        // Pyramiding grouped view (owner-approved). Now that every condition's final
        // rating is persisted, assign each to its DETERMINISTIC canonical pyramiding
        // group (PyramidingGroups): set pyramidGroup, mark the highest-rated member
        // pyramidPrimary, give the absorbed members the "rated together … strongest
        // counts" pyramidReason (the same rows PyramidingRules excludes from the
        // combined math — kept consistent), stamp the group's effective rating on every
        // member, and attach the advisory TBI overlap note. This REPLACES the unreliable
        // LLM-set pyramidGroup with a stable one. Runs here — after ratings persist,
        // before verify — so the group/reason persist and flow into the verify prompt.
        // Gated by pyramiding-groups (default ON); OFF reverts to today's LLM-set values.
        if (pyramidingGroupsEnabled) {
            int grouped = coordinator.assignPyramidingGroups(conditions);
            if (grouped > 0) {
                for (IdentifiedCondition cond : conditions) {
                    conditionRepository.save(cond);
                }
                log.info("[synthesis] pyramiding-groups assigned {} condition(s) for claim {}",
                        grouped, claim.getId());
            }
        }

        List<Map<String, Object>> conditionMaps = new ArrayList<>();
        for (IdentifiedCondition c : conditions) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", c.getName());
            m.put("vasrd_code", c.getVasrdCode());
            m.put("body_system", c.getBodySystem());
            m.put("estimated_rating", c.getEstimatedRating());
            m.put("rating_rationale", c.getRatingRationale());
            m.put("is_presumptive", c.getIsPresumptive());
            m.put("presumptive_basis", c.getPresumptiveBasis());
            m.put("triad_diagnosis", c.getTriadDiagnosis());
            m.put("triad_in_service", c.getTriadInService());
            m.put("triad_nexus", c.getTriadNexus());
            conditionMaps.add(m);
        }

        LlmJobRequest verifyReq = verificationAgent.buildRequest(conditionMaps, claim.getId(), claim.getUserId());
        pipelineJobRepository.deleteByClaimIdAndStage(claim.getId(), "synthesis_verify");
        UUID verifyJobId = llmJobService.submit(verifyReq);
        pipelineJobRepository.save(new ClaimPipelineJob(
                claim.getId(), "synthesis_verify", verifyJobId, null, null));

        claim.setSynthesisState(State.VERIFYING.name());
        claimRepository.save(claim);
        log.info("[synthesis] RATING → VERIFYING for claim {}", claim.getId());
    }

    // -------------------------------------------------------------------------
    // VERIFYING → COMPLETE
    // -------------------------------------------------------------------------

    private void doCompleteIfReady(Claim claim) {
        List<ClaimPipelineJob> verifyPjobs = pipelineJobRepository
                .findByClaimIdAndStage(claim.getId(), "synthesis_verify");
        if (verifyPjobs.isEmpty()) return;

        Set<UUID> jobIds = collectJobIds(verifyPjobs);
        if (!llmJobService.allTerminal(jobIds)) return;
        if (!llmJobService.allSucceeded(jobIds)) {
            failSynthesis(claim, "synthesis_verify", jobIds);
            return;
        }

        // Parse issues
        UUID verifyJobId = verifyPjobs.get(0).getLlmJobId();
        LlmJobResult verifyResult = llmJobService.getResult(verifyJobId).orElseThrow();
        List<Map<String, Object>> issues = verificationAgent.parseResponse(verifyResult);

        // Apply corrections to the in-run (pending) generation.
        List<IdentifiedCondition> conditions = currentRunConditions(claim);
        List<Map<String, Object>> conditionMaps = new ArrayList<>();
        for (IdentifiedCondition c : conditions) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", c.getName());
            m.put("vasrd_code", c.getVasrdCode());
            m.put("estimated_rating", c.getEstimatedRating());
            conditionMaps.add(m);
        }
        coordinator.applyCorrections(conditionMaps, issues);

        // Apply pyramid corrections back to DB. With the deterministic pyramiding-group
        // pass ON (default), the group/reason were already assigned deterministically in
        // doVerifyIfReady and are the source of truth — the LLM verifier's pyramid_flag
        // must NOT clobber them (that is the whole point of moving off the unreliable
        // LLM grouping). So this legacy LLM-driven correction only runs with the flag OFF.
        if (!pyramidingGroupsEnabled) {
            for (int i = 0; i < conditions.size() && i < conditionMaps.size(); i++) {
                IdentifiedCondition cond = conditions.get(i);
                Map<String, Object> m = conditionMaps.get(i);
                if (Boolean.TRUE.equals(m.get("pyramid_flag"))) {
                    cond.setPyramidGroup((String) m.getOrDefault("name", ""));
                    cond.setPyramidReason((String) m.get("pyramid_reason"));
                    conditionRepository.save(cond);
                }
            }
        }

        // Mission 5b — ATOMIC generation flip. doCompleteIfReady runs inside the
        // single @Transactional advance() call, so activating the new generation
        // (clear PENDING) and retiring the prior one (set superseded_by →
        // replacement id or tombstone) commit together: an external reader sees
        // exactly ONE full generation before and after, never zero and never both.
        // The prior generation is whatever was active (superseded_by IS NULL)
        // right up to this instant — read it BEFORE activating the new rows so the
        // two sets are disjoint, then activate, then supersede pointing old → new.
        if (incrementalEnabled) {
            List<IdentifiedCondition> priorGeneration =
                    conditionRepository.findByClaimIdAndSupersededByIsNull(claim.getId());
            generationService.activatePendingGeneration(conditions);
            generationService.supersedePriorGeneration(priorGeneration, conditions);
        }

        // Determine model name from verify job
        String modelName = llmJobRepository.findById(verifyJobId)
                .map(j -> j.getModelName())
                .orElse("unknown");

        // markSynthesisComplete resets synthesisState to null and sets lastSynthesisAt
        analysisScheduler.markSynthesisComplete(claim.getId(), modelName);
        log.info("[synthesis] VERIFYING → COMPLETE for claim {}", claim.getId());
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    /**
     * The conditions THIS run operates on (rate/verify/complete stages). With the
     * incremental flag ON the new generation is written PENDING — invisible to
     * external readers — so the in-run stages must read the pending rows, not the
     * still-active prior generation. With the flag OFF there is no pending marker
     * and this is the full set, exactly the legacy {@code findByClaimId}.
     */
    private List<IdentifiedCondition> currentRunConditions(Claim claim) {
        if (incrementalEnabled) {
            return conditionRepository.findByClaimIdAndSupersededBy(
                    claim.getId(), ConditionGenerationService.PENDING_MARKER);
        }
        return conditionRepository.findByClaimId(claim.getId());
    }

    private State parseState(String s) {
        return s == null ? State.NONE : State.valueOf(s);
    }

    private Set<UUID> collectJobIds(List<ClaimPipelineJob> pjobs) {
        Set<UUID> ids = new HashSet<>();
        for (ClaimPipelineJob pj : pjobs) ids.add(pj.getLlmJobId());
        return ids;
    }

    /**
     * Terminal failure: one or more jobs in the stage FAILED. Previously a
     * FAILED job left allSucceeded false forever, wedging the claim mid-
     * pipeline with synthesisInProgress=true and no surfaced error.
     */
    private void failSynthesis(Claim claim, String stage, Set<UUID> jobIds) {
        String error = stage + "_failed: " + firstFailureMessage(jobIds);
        log.error("[synthesis] Stage {} FAILED for claim {} — {}", stage, claim.getId(), error);
        analysisScheduler.markSynthesisFailed(claim.getId(), error);
    }

    private String firstFailureMessage(Set<UUID> jobIds) {
        return llmJobRepository.findAllById(jobIds).stream()
                .filter(j -> j.getStatus() == com.afterduty.model.LlmJob.Status.FAILED)
                .map(j -> j.getErrorMessage())
                .filter(Objects::nonNull)
                .findFirst()
                .orElse("LLM job failed");
    }
}
