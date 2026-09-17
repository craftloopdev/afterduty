package com.afterduty.service.synthesis;

import com.afterduty.model.Atom;
import com.afterduty.model.IdentifiedCondition;
import com.afterduty.model.ServiceProfile;
import com.afterduty.repository.AtomRepository;
import com.afterduty.repository.ServiceProfileRepository;
import com.afterduty.service.PresumptiveRulesService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Thin coordinator for synthesis helpers that don't belong to any specific state machine step.
 *
 * The active synthesis pipeline is now driven by SynthesisStateMachine. This class
 * exposes only the context-building and correction helpers that the state machine needs.
 *
 * {@code ServiceProfileRepository} and {@code PresumptiveRulesService} are optional via
 * setter injection so that test slices (SynthesisStateMachineTest, ProviderSwapTest) that
 * import EnhancedSynthesisOrchestrator without those beans can still wire the context.
 * All methods that use those dependencies are null-guarded.
 */
@Service
public class EnhancedSynthesisOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(EnhancedSynthesisOrchestrator.class);

    @Nullable
    private ServiceProfileRepository serviceProfileRepository;

    @Nullable
    private PresumptiveRulesService presumptiveRulesService;

    @Nullable
    private AtomRepository atomRepository;
    private com.afterduty.service.DomainCorrectionsService domainCorrectionsService;

    /**
     * Rating honesty — the curated code → required-objective-evidence map. Always
     * present in production (a @Service); test slices that construct a bare
     * orchestrator get the default no-arg instance below so {@code assessRatingEvidence}
     * still works without wiring. Never null.
     */
    private RatingEvidenceRequirements ratingEvidenceRequirements = new RatingEvidenceRequirements();

    /**
     * Pyramiding grouped view — the deterministic code → canonical-group map. Always
     * present in production (a @Component); test slices that construct a bare
     * orchestrator get the default no-arg instance below so {@code assignPyramidingGroups}
     * works without wiring. Never null.
     */
    private PyramidingGroups pyramidingGroups = new PyramidingGroups();

    public EnhancedSynthesisOrchestrator() {
        // No-arg constructor: dependencies injected via optional setters below.
    }

    @Autowired(required = false)
    public void setServiceProfileRepository(ServiceProfileRepository serviceProfileRepository) {
        this.serviceProfileRepository = serviceProfileRepository;
    }

    @Autowired(required = false)
    public void setRatingEvidenceRequirements(RatingEvidenceRequirements ratingEvidenceRequirements) {
        if (ratingEvidenceRequirements != null) this.ratingEvidenceRequirements = ratingEvidenceRequirements;
    }

    @Autowired(required = false)
    public void setPyramidingGroups(PyramidingGroups pyramidingGroups) {
        if (pyramidingGroups != null) this.pyramidingGroups = pyramidingGroups;
    }

    @Autowired(required = false)
    public void setPresumptiveRulesService(PresumptiveRulesService presumptiveRulesService) {
        this.presumptiveRulesService = presumptiveRulesService;
    }

    @Autowired(required = false)
    public void setDomainCorrectionsService(com.afterduty.service.DomainCorrectionsService domainCorrectionsService) {
        this.domainCorrectionsService = domainCorrectionsService;
    }

    @Autowired(required = false)
    public void setAtomRepository(AtomRepository atomRepository) {
        this.atomRepository = atomRepository;
    }

    /**
     * Apply verification corrections to conditions.
     * Currently handles rating_mismatch and invalid_code issues.
     * Returns number of corrections applied.
     */
    public int applyCorrections(List<Map<String, Object>> conditions, List<Map<String, Object>> issues) {
        int applied = 0;
        for (Map<String, Object> issue : issues) {
            String conditionName = (String) issue.get("condition_name");
            String issueType = (String) issue.get("issue_type");
            String suggestedFix = (String) issue.get("suggested_fix");

            if (conditionName == null || issueType == null) continue;

            Optional<Map<String, Object>> match = conditions.stream()
                    .filter(c -> conditionName.equalsIgnoreCase((String) c.get("name")))
                    .findFirst();

            if (match.isEmpty()) continue;
            Map<String, Object> condition = match.get();

            switch (issueType) {
                case "pyramiding" -> {
                    condition.put("pyramid_flag", true);
                    condition.put("pyramid_reason", suggestedFix);
                    applied++;
                }
                case "rating_mismatch" -> {
                    condition.put("rating_review_needed", true);
                    condition.put("rating_review_reason", suggestedFix);
                    applied++;
                }
                case "invalid_code" -> {
                    condition.put("code_review_needed", true);
                    condition.put("code_review_reason", suggestedFix);
                    applied++;
                }
                default -> log.debug("Verification issue for {}: {} - {}", conditionName, issueType, suggestedFix);
            }
        }
        return applied;
    }

    @SuppressWarnings("unchecked")
    public IdentifiedCondition mapToCondition(Map<String, Object> condMap, Long claimId) {
        IdentifiedCondition condition = IdentifiedCondition.builder()
                .claimId(claimId)
                .name((String) condMap.getOrDefault("name", "Unknown Condition"))
                .vasrdCode((String) condMap.get("vasrd_code"))
                .bodySystem((String) condMap.get("body_system"))
                .triadDiagnosis((Map<String, Object>) condMap.get("triad_diagnosis"))
                .triadInService((Map<String, Object>) condMap.get("triad_in_service"))
                .triadNexus((Map<String, Object>) condMap.get("triad_nexus"))
                .isPresumptive(condMap.get("is_presumptive") instanceof Boolean b ? b : false)
                .presumptiveBasis((String) condMap.get("presumptive_basis"))
                // Secondary-aware analysis: the identify LLM emits secondary_to (the
                // primary condition's name) when this is a 38 CFR 3.310 secondary
                // claim. Mirrors the presumptive_basis mapping above. Null/blank ⇒
                // direct claim (today's behavior). reconcileSecondary later reframes
                // the in-service leg to "primary is service-connected" for these.
                .secondaryTo(blankToNull((String) condMap.get("secondary_to")))
                .estimatedRating(condMap.get("estimated_rating") instanceof Number n ? n.intValue() : 0)
                .ratingRationale((String) condMap.get("rating_rationale"))
                .confidence(condMap.get("confidence") instanceof Number n ? n.doubleValue() : 0.5)
                .build();

        if (Boolean.TRUE.equals(condMap.get("pyramid_flag"))) {
            condition.setPyramidGroup((String) condMap.getOrDefault("name", ""));
            condition.setPyramidReason((String) condMap.get("pyramid_reason"));
        }

        return condition;
    }

    public String buildServiceContext(Long userId) {
        if (serviceProfileRepository == null) return "No service profile on file.\n";
        return serviceProfileRepository.findByUserId(userId)
                .map(profile -> {
                    StringBuilder sb = new StringBuilder();
                    if (profile.getBranch() != null) sb.append("Branch: ").append(profile.getBranch()).append("\n");
                    if (profile.getServiceStart() != null) sb.append("Service start: ").append(profile.getServiceStart()).append("\n");
                    if (profile.getServiceEnd() != null) sb.append("Service end: ").append(profile.getServiceEnd()).append("\n");
                    if (profile.getMos() != null) sb.append("MOS: ").append(profile.getMos()).append("\n");
                    if (profile.getDeployments() != null) sb.append("Deployments: ").append(profile.getDeployments()).append("\n");
                    if (profile.getExposureRisks() != null) sb.append("Exposure risks: ").append(profile.getExposureRisks()).append("\n");
                    return sb.toString();
                })
                .orElse("No service profile on file.\n");
    }

    public String buildPresumptiveContext(Long userId) {
        if (serviceProfileRepository == null || presumptiveRulesService == null) {
            return "No service profile -- cannot check presumptive eligibility.\n";
        }
        return serviceProfileRepository.findByUserId(userId)
                .map(profile -> {
                    Map<String, Object> profileMap = Map.of(
                            "deployments", profile.getDeployments() != null ? profile.getDeployments() : List.of(),
                            "exposure_risks", profile.getExposureRisks() != null ? profile.getExposureRisks() : List.of(),
                            "service_start", profile.getServiceStart() != null ? profile.getServiceStart() : "",
                            "service_end", profile.getServiceEnd() != null ? profile.getServiceEnd() : ""
                    );
                    List<Map<String, String>> matches = presumptiveRulesService.checkPresumptiveConnections(profileMap);
                    if (matches.isEmpty()) return withCorrections("No presumptive conditions matched.\n");

                    StringBuilder sb = new StringBuilder();
                    sb.append("Veteran may qualify for ").append(matches.size()).append(" presumptive conditions:\n");
                    for (Map<String, String> match : matches) {
                        sb.append("- ").append(match.get("condition"))
                                .append(" (VASRD ").append(match.get("vasrd_code"))
                                .append(", basis: ").append(match.get("basis")).append(")");
                        if ("true".equals(match.get("undiagnosed"))) {
                            sb.append(" — UNDIAGNOSED-illness lane only: does NOT apply to a diagnosed condition");
                        }
                        sb.append('\n');
                    }
                    return withCorrections(sb.toString());
                })
                .orElse(withCorrections("No service profile -- cannot check presumptive eligibility.\n"));
    }

    /** Append the self-correction KB's prompt corpus (empty KB → unchanged text). */
    private String withCorrections(String context) {
        if (domainCorrectionsService == null) return context;
        String corpus = domainCorrectionsService.promptCorpus();
        return corpus.isEmpty() ? context : context + "\n" + corpus;
    }

    /**
     * Deterministic "infer then confirm" reconciliation (owner-approved). The
     * identify LLM sets {@code is_presumptive} only from its own boolean, and the
     * PACT-Act rules engine historically only read a manually entered
     * {@link ServiceProfile} the veteran rarely fills in — so a veteran whose Iraq
     * deployment / burn-pit exposure exists only as extracted evidence atoms got
     * {@code is_presumptive=false} and every downstream layer wrongly recommended a
     * nexus letter. This pass reconciles each mapped condition against the same
     * rules engine, using signals DERIVED FROM EVIDENCE ATOMS (merged with any
     * stored ServiceProfile), and on a match flips a PROVISIONAL presumptive —
     * clearly labeled "pending your confirmation" — and rewrites the stale nexus
     * triad so the gap analyzer stops asking for a nexus letter.
     *
     * <p>PROVISIONAL BY DESIGN: the inference has no confirmed service dates, so
     * the basis and nexus text are explicitly qualified. This never emits an
     * unqualified hard presumptive assertion.
     *
     * <p>No-op (returns 0) when the optional {@link ServiceProfileRepository},
     * {@link PresumptiveRulesService}, or {@link AtomRepository} beans are absent —
     * mirroring the null-guards in {@link #buildPresumptiveContext}.
     *
     * @return the number of conditions flipped to provisional presumptive.
     */
    public int reconcilePresumptiveFromEvidence(List<IdentifiedCondition> conditions,
                                                 Long claimId, Long userId) {
        if (serviceProfileRepository == null || presumptiveRulesService == null || atomRepository == null) {
            return 0;
        }
        if (conditions == null || conditions.isEmpty()) return 0;

        // (a) Build the merged service profile: stored ServiceProfile (same shape
        // as buildPresumptiveContext) UNIONed with the atom-derived signals.
        Map<String, Object> mergedProfile = buildMergedServiceProfile(claimId, userId);

        // (b) Run the rules engine over the merged profile.
        List<Map<String, String>> matches = presumptiveRulesService.checkPresumptiveConnections(mergedProfile);
        if (matches.isEmpty()) return 0;

        // (c) vasrd_code -> basis. First match for a code wins (rules are ordered
        // PACT → Agent Orange → Gulf War; the engine already dedupes per rule).
        // UNDIAGNOSED-illness lanes are EXCLUDED: they share codes with common
        // diagnosed conditions (8100 migraines, 6847 sleep apnea …) and a bare
        // code join falsely flagged those as Gulf War presumptive (DC-2026-001/
        // -002 in domain-corrections.json). Every condition row here carries a
        // diagnosis by construction, so the 3.317 sign/symptom lanes can never
        // legitimately attach through this join.
        Map<String, String> basisByCode = new LinkedHashMap<>();
        for (Map<String, String> match : matches) {
            if ("true".equals(match.get("undiagnosed"))) continue;
            String code = match.get("vasrd_code");
            String basis = match.get("basis");
            if (code != null && basis != null) basisByCode.putIfAbsent(code, basis);
        }

        // (d) Flip each newly-matched, not-yet-presumptive condition.
        int flipped = 0;
        for (IdentifiedCondition condition : conditions) {
            String code = condition.getVasrdCode();
            if (code == null) continue;
            String basis = basisByCode.get(code);
            if (basis == null) continue;
            if (Boolean.TRUE.equals(condition.getIsPresumptive())) continue;

            condition.setIsPresumptive(true);
            condition.setPresumptiveBasis(basis
                    + " (provisional — confirm your qualifying service dates/location to finalize)");
            condition.setTriadNexus(buildProvisionalNexus(condition.getTriadNexus(), basis));
            flipped++;
            log.info("[synthesis] provisional presumptive flip for claim {} vasrd {} — basis: {}",
                    claimId, code, basis);
        }
        return flipped;
    }

    /**
     * Stored ServiceProfile (deployments + exposure_risks + service dates, the same
     * shape {@link #buildPresumptiveContext} uses) UNIONed with the signals derived
     * from this claim's live (non-superseded) evidence atoms. The atom-derived
     * deployments/exposures augment — never replace — the stored profile, so a
     * veteran who filled in nothing still gets the inference and one who filled it
     * in keeps their confirmed data.
     */
    private Map<String, Object> buildMergedServiceProfile(Long claimId, Long userId) {
        // Atom-derived signals from this claim's live evidence text.
        List<String> atomTexts = atomRepository.findByClaimIdAndSupersededByIsNull(claimId).stream()
                .map(Atom::getValue)
                .filter(Objects::nonNull)
                .toList();
        Map<String, Object> derived = presumptiveRulesService.deriveServiceProfileFromText(atomTexts);

        // Stored profile (may be absent — the common case this feature targets).
        Optional<ServiceProfile> stored = serviceProfileRepository.findByUserId(userId);

        List<Object> deployments = new ArrayList<>();
        List<Object> exposureRisks = new ArrayList<>();
        String serviceStart = "";
        String serviceEnd = "";

        if (stored.isPresent()) {
            ServiceProfile profile = stored.get();
            if (profile.getDeployments() != null) deployments.addAll(profile.getDeployments());
            if (profile.getExposureRisks() != null) exposureRisks.addAll(profile.getExposureRisks());
            if (profile.getServiceStart() != null) serviceStart = profile.getServiceStart();
            if (profile.getServiceEnd() != null) serviceEnd = profile.getServiceEnd();
        }

        // Union in the atom-derived signals (the engine already normalizes/dedupes
        // when matching, so exact-dup filtering here is unnecessary).
        Object derivedDeployments = derived.get("deployments");
        if (derivedDeployments instanceof List<?> l) deployments.addAll(l);
        Object derivedExposures = derived.get("exposure_risks");
        if (derivedExposures instanceof List<?> l) exposureRisks.addAll(l);

        Map<String, Object> merged = new LinkedHashMap<>();
        merged.put("deployments", deployments);
        merged.put("exposure_risks", exposureRisks);
        merged.put("service_start", serviceStart);
        merged.put("service_end", serviceEnd);
        return merged;
    }

    /**
     * Rewrite a condition's nexus triad leg for an established (provisional)
     * presumption. Preserves the existing map shape ({@code status}, {@code
     * evidence} list, {@code confidence}) and any bullet that documents real
     * qualifying-service evidence (e.g. the Iraq-deployment / burn-pit atoms);
     * only the "no nexus letter" / private-nexus / direct-service-connection asks
     * are dropped. Sets the strength to STRONG (the strongest value the identify
     * schema uses) and prepends the presumed-nexus statement. Fabricates no
     * evidence — it never invents a qualifying fact, it only reclassifies the
     * nexus leg the presumption already satisfies.
     */
    private Map<String, Object> buildProvisionalNexus(Map<String, Object> existingNexus, String basis) {
        Map<String, Object> nexus = new LinkedHashMap<>();

        // Preserve the original status KEY name if present (schema uses "status"),
        // and set the strongest value the identify schema uses ("STRONG").
        nexus.put("status", "STRONG");

        List<String> evidence = new ArrayList<>();
        evidence.add("Nexus presumed under " + basis
                + " (PACT Act presumptive) — no private nexus opinion (IMO) required");
        evidence.add("Provisional pending confirmation of qualifying service");

        // Preserve any pre-existing bullet that documents REAL qualifying-service
        // evidence; drop only the stale "no nexus letter" / IMO / direct-connection asks.
        if (existingNexus != null) {
            Object priorEvidence = existingNexus.get("evidence");
            if (priorEvidence instanceof List<?> l) {
                for (Object item : l) {
                    if (item == null) continue;
                    String bullet = item.toString();
                    if (isStaleNexusAsk(bullet)) continue;
                    if (!evidence.contains(bullet)) evidence.add(bullet);
                }
            }
        }
        nexus.put("evidence", evidence);
        nexus.put("confidence", 0.9);
        return nexus;
    }

    /**
     * True when a nexus-leg bullet is a stale "you still need a nexus letter"
     * ask that no longer applies once presumption is established. Matched
     * case-insensitively on the phrases the identify/synthesis prompts emit.
     */
    private boolean isStaleNexusAsk(String bullet) {
        String lower = bullet.toLowerCase();
        return lower.contains("nexus letter")
                || lower.contains("nexus imo")
                || lower.contains("imo letter")
                || lower.contains("no specific nexus")
                || lower.contains("no nexus")
                || lower.contains("missing nexus")
                || lower.contains("direct service connection")
                || lower.contains("direct service-connection");
    }

    // =====================================================================
    // Rating honesty — objective-evidence tagging + evidence-based confidence.
    // =====================================================================

    /**
     * Deterministic rating-honesty post-pass (owner-approved), mirroring
     * {@link #reconcilePresumptiveFromEvidence} / {@link #reconcileSecondary} in shape.
     *
     * <p>The LLM stores a SELF-REPORTED confidence that can read 90%+ even when the
     * OBJECTIVE measure the code's rating tiers hinge on is absent (asthma 6602 rated
     * 30% with no PFT/FEV-1 on file). For each condition whose VASRD code is in the
     * curated {@link RatingEvidenceRequirements} map, this pass checks whether that
     * measure appears in the condition's own supporting atoms (preferred) or, failing
     * attribution, the claim's live atom corpus:
     * <ul>
     *   <li><b>Measure ABSENT</b> → set {@code ratingEvidenceNote} ("Estimate — needs
     *       &lt;measure&gt; to confirm…") AND temper the served confidence with the
     *       missing-measure penalty (see {@link #temperedConfidence}). The estimated
     *       rating NUMBER is never changed.</li>
     *   <li><b>Measure PRESENT</b> → no note; confidence is still tempered toward
     *       evidence completeness but NOT penalized.</li>
     * </ul>
     * UNMAPPED codes are left entirely untouched — never guess a requirement.
     *
     * <p>Flag-gated by the caller ({@code SynthesisStateMachine},
     * {@code va-claim.analysis.rating-honesty}). No-op (returns 0) on a null/empty list.
     * Atom lookups degrade safely to an empty corpus when {@link AtomRepository} is
     * absent (a mapped code with no readable atoms then always tags — the conservative,
     * honest default).
     *
     * @return the number of conditions whose confidence was tempered and/or tagged.
     */
    public int assessRatingEvidence(List<IdentifiedCondition> conditions, Long claimId) {
        if (conditions == null || conditions.isEmpty()) return 0;

        // Claim-wide live atom text, fetched at most once (only when a mapped code is
        // present AND lacks its own attribution). Absent AtomRepository ⇒ empty corpus.
        List<String> claimAtomTexts = null;

        int assessed = 0;
        for (IdentifiedCondition condition : conditions) {
            Optional<RatingEvidenceRequirements.Requirement> reqOpt =
                    ratingEvidenceRequirements.forCode(condition.getVasrdCode());
            if (reqOpt.isEmpty()) continue;  // unmapped code — never touched
            RatingEvidenceRequirements.Requirement req = reqOpt.get();

            // Prefer the condition's OWN attributed atoms (identify supporting_atom_ids);
            // fall back to the whole live corpus when nothing is attributed. Scoping to
            // the condition's atoms avoids a PFT for a DIFFERENT respiratory condition
            // spuriously satisfying this one.
            List<String> texts = attributedAtomTexts(condition);
            if (texts == null) {
                if (claimAtomTexts == null) claimAtomTexts = liveClaimAtomTexts(claimId);
                texts = claimAtomTexts;
            }
            boolean present = ratingEvidenceRequirements.measurePresent(req, texts);

            double completeness = triadCompleteness(condition);
            double llmConfidence = condition.getConfidence() != null ? condition.getConfidence() : 0.5;
            double tempered = temperedConfidence(llmConfidence, completeness, present);
            condition.setConfidence(tempered);

            if (!present) {
                condition.setRatingEvidenceNote("Estimate — needs " + req.measureName()
                        + " to confirm. This rating is inferred from the other evidence on file.");
                log.info("[synthesis] rating-honesty tag for claim {} vasrd {} — missing {}; "
                                + "confidence {} → {}", claimId, condition.getVasrdCode(),
                        req.measureName(), llmConfidence, tempered);
            } else {
                // Present ⇒ no note. Clear any stale note from a prior run (defensive;
                // fresh rows have none). Confidence still tempered toward completeness.
                condition.setRatingEvidenceNote(null);
                log.debug("[synthesis] rating-honesty: claim {} vasrd {} has {}; confidence {} → {}",
                        claimId, condition.getVasrdCode(), req.measureName(), llmConfidence, tempered);
            }
            assessed++;
        }
        return assessed;
    }

    /**
     * The served confidence for a rating, tied to EVIDENCE COMPLETENESS rather than the
     * model's self-report. Simple, documented, monotone:
     *
     * <pre>
     *   base = min(llmConfidence, evidenceCompletenessScore)
     *   if the required objective measure is MISSING:
     *       tempered = max(base * 0.6, 0.40)   // penalize, but never below a 0.40 floor
     *   else:
     *       tempered = base                    // present: capped at completeness, not penalized
     * </pre>
     *
     * Rationale: {@code min(...)} means a rating can never be served MORE confident than
     * the triad supports — an 0.88 self-report over a half-supported triad is already
     * pulled down. The ×0.6 missing-measure penalty then guarantees an estimate resting
     * on an ABSENT objective test can't show 90%+ (e.g. 0.88 self-report, full triad →
     * base 0.88 → 0.53 when the PFT is missing). The 0.40 floor keeps a plausible
     * inferred estimate from reading as near-zero (it IS inferred from real other
     * evidence). Inputs are clamped to [0,1]; output is in [0.40, 1.0] when penalized,
     * [0,1] otherwise.
     */
    double temperedConfidence(double llmConfidence, double evidenceCompletenessScore,
                              boolean measurePresent) {
        double llm = clamp01(llmConfidence);
        double completeness = clamp01(evidenceCompletenessScore);
        double base = Math.min(llm, completeness);
        if (measurePresent) return base;
        return Math.max(base * 0.6, 0.40);
    }

    /**
     * Evidence-completeness score in [0,1] derived from the diagnosis / in-service /
     * nexus triad: the fraction of the three legs that are STRONG or MODERATE. STRONG
     * and MODERATE both count as a supported leg (either can carry a claim); PARTIAL /
     * WEAK / MISSING / absent legs do not. A fully-supported triad scores 1.0, a
     * two-of-three 0.67, none 0.0. Null triad maps (legacy/odd rows) count as unsupported.
     */
    double triadCompleteness(IdentifiedCondition condition) {
        int supported = 0;
        if (isSupportedLeg(condition.getTriadDiagnosis())) supported++;
        if (isSupportedLeg(condition.getTriadInService())) supported++;
        if (isSupportedLeg(condition.getTriadNexus())) supported++;
        return supported / 3.0;
    }

    /** A triad leg counts as "supported" when its status is STRONG or MODERATE. */
    private boolean isSupportedLeg(Map<String, Object> leg) {
        String status = legStatus(leg);
        return "STRONG".equals(status) || "MODERATE".equals(status);
    }

    /**
     * Texts of the atoms the identify model attributed to THIS condition
     * ({@code supportingAtomIds}), or null when nothing usable is attributed (caller
     * then falls back to the whole live corpus). Requires {@link AtomRepository}; null
     * when absent.
     */
    @Nullable
    private List<String> attributedAtomTexts(IdentifiedCondition condition) {
        if (atomRepository == null) return null;
        List<Long> ids = condition.getSupportingAtomIds();
        if (ids == null || ids.isEmpty()) return null;
        List<String> texts = new ArrayList<>();
        for (Atom a : atomRepository.findAllById(ids)) {
            if (a.getValue() != null) texts.add(a.getValue());
        }
        return texts.isEmpty() ? null : texts;
    }

    /** Live (non-superseded) atom texts for a claim; empty when AtomRepository absent. */
    private List<String> liveClaimAtomTexts(Long claimId) {
        if (atomRepository == null) return List.of();
        return atomRepository.findByClaimIdAndSupersededByIsNull(claimId).stream()
                .map(Atom::getValue)
                .filter(Objects::nonNull)
                .toList();
    }

    private static double clamp01(double v) {
        if (Double.isNaN(v)) return 0.0;
        return Math.max(0.0, Math.min(1.0, v));
    }

    // =====================================================================
    // Secondary-aware analysis (38 CFR 3.310) — see reconcileSecondary.
    // =====================================================================

    /** How confident we are that a secondary's PRIMARY is service-connected. */
    public enum PrimaryScStatus {
        /** An atom/fact states the primary is VA service-connected / rated. */
        KNOWN,
        /** The primary is itself a STRONG direct claim in THIS analysis (likely, not yet granted). */
        LIKELY,
        /** Neither — we must ASK the veteran to confirm the primary's SC status. */
        UNKNOWN
    }

    /**
     * Deterministic secondary-aware triad reconciliation (38 CFR 3.310),
     * mirroring {@link #reconcilePresumptiveFromEvidence} in shape and spirit.
     *
     * <p>A secondary claim (e.g. GERD secondary to PTSD) has NO separate in-service
     * event — the in-service requirement is satisfied THROUGH the service-connected
     * PRIMARY. The identify LLM historically force-fit the direct-service-connection
     * triad and marked the in-service leg PARTIAL with secondary-link content, so a
     * potentially COMPLETE secondary claim looked incomplete. This pass, for each
     * condition whose {@code secondaryTo} is set, REWRITES the in-service triad leg
     * to represent the load-bearing question — <b>is the PRIMARY service-connected?</b>
     * — instead of a fake in-service event.
     *
     * <p>Primary-SC status is resolved from what is KNOWN (never asserted):
     * <ul>
     *   <li>{@code KNOWN} — an evidence atom for THIS claim names the primary and
     *       states it is VA service-connected / rated / carries a %. In-service leg
     *       becomes STRONG.</li>
     *   <li>{@code LIKELY} — the primary is itself another identified condition in
     *       this same analysis whose OWN triad is strong (a strong direct claim,
     *       likely to be granted but not yet). In-service leg becomes MODERATE,
     *       clearly qualified "likely — not yet granted".</li>
     *   <li>{@code UNKNOWN} — neither. In-service leg becomes MISSING with a clear
     *       "confirm your &lt;primary&gt; is VA service-connected" bullet, so the
     *       downstream gap analyzer asks the veteran.</li>
     * </ul>
     *
     * <p>PROVISIONAL / never over-asserts: the leg text is explicitly qualified and
     * this pass NEVER claims the primary IS service-connected unless an atom says so.
     * It does NOT touch the rating number (the secondary is rated on its own code,
     * e.g. GERD 7346) — only the service-connection framing/readiness. Real
     * nexus/aggravation bullets on the nexus leg are preserved.
     *
     * <p>No-op (returns 0) when {@link AtomRepository} is absent (mirrors the
     * null-guards elsewhere) or when no condition is secondary. Flag-gated by the
     * caller ({@code SynthesisStateMachine}, {@code va-claim.analysis.secondary-aware}).
     *
     * @return the number of secondary conditions whose in-service leg was reframed.
     */
    public int reconcileSecondary(List<IdentifiedCondition> conditions, Long claimId) {
        if (conditions == null || conditions.isEmpty()) return 0;

        // Live atom text for this claim (for the KNOWN-primary-SC scan). Absent
        // AtomRepository ⇒ empty corpus, so KNOWN can never be inferred and the pass
        // degrades safely to LIKELY/UNKNOWN.
        List<String> atomTexts = (atomRepository == null)
                ? List.of()
                : atomRepository.findByClaimIdAndSupersededByIsNull(claimId).stream()
                        .map(Atom::getValue)
                        .filter(Objects::nonNull)
                        .toList();

        int reframed = 0;
        for (IdentifiedCondition condition : conditions) {
            String primary = condition.getSecondaryTo();
            if (primary == null || primary.isBlank()) continue;

            PrimaryScStatus status = resolvePrimaryScStatus(primary, condition, conditions, atomTexts);
            condition.setTriadInService(buildPrimaryScLeg(condition.getTriadInService(), primary, status));
            appendSecondaryDependencyNote(condition, primary, status);
            reframed++;
            log.info("[synthesis] secondary-aware reframe for claim {} — '{}' secondary to '{}', primary-SC={}",
                    claimId, condition.getName(), primary, status);
        }
        return reframed;
    }

    /**
     * Resolve how confident we are the PRIMARY is service-connected, in priority
     * order KNOWN → LIKELY → UNKNOWN. Never asserts SC — only reports what the
     * evidence/analysis supports.
     */
    private PrimaryScStatus resolvePrimaryScStatus(String primary, IdentifiedCondition secondary,
                                                    List<IdentifiedCondition> conditions,
                                                    List<String> atomTexts) {
        // (1) KNOWN — an atom names the primary AND states VA service-connection / rating.
        String primaryLower = primary.toLowerCase();
        List<String> primaryTokens = significantTokens(primary);
        for (String text : atomTexts) {
            if (text == null) continue;
            String lower = text.toLowerCase();
            if (!mentionsPrimary(lower, primaryLower, primaryTokens)) continue;
            if (statesServiceConnected(lower)) return PrimaryScStatus.KNOWN;
        }

        // (2) LIKELY — the primary is itself another identified condition in THIS
        // analysis with a strong direct claim (fuzzy name match, excluding self).
        for (IdentifiedCondition other : conditions) {
            if (other == secondary) continue;
            if (other.getName() == null) continue;
            if (!namesMatchFuzzy(other.getName(), primary)) continue;
            // The primary matched an atom (KNOWN) already returned above; here it is
            // an in-claim condition. Treat it as LIKELY when its OWN direct claim is
            // strong. (A secondary-of-a-secondary primary still counts — its own
            // reconcile pass frames its readiness.)
            if (isStrongDirectClaim(other)) return PrimaryScStatus.LIKELY;
            // Present but weak → we still don't KNOW it's service-connected; ask.
            return PrimaryScStatus.UNKNOWN;
        }

        // (3) UNKNOWN — no SC atom, primary not an in-claim condition.
        return PrimaryScStatus.UNKNOWN;
    }

    /**
     * Rewrite the "in-service" triad leg to represent the PRIMARY-service-connected
     * requirement (NOT a fake in-service event). Preserves the map shape
     * ({@code status}, {@code evidence} list, {@code confidence}) and any real
     * primary-link bullet already present; drops only stale fake-in-service asks.
     * Never asserts SC unless status is KNOWN.
     */
    private Map<String, Object> buildPrimaryScLeg(Map<String, Object> existing, String primary,
                                                   PrimaryScStatus status) {
        Map<String, Object> leg = new LinkedHashMap<>();
        List<String> evidence = new ArrayList<>();
        String legStatus;
        double confidence;

        switch (status) {
            case KNOWN -> {
                legStatus = "STRONG";
                confidence = 0.9;
                evidence.add("Primary condition (" + primary + ") is VA service-connected — the in-service "
                        + "requirement for this secondary claim is met through the primary (38 CFR 3.310)");
            }
            case LIKELY -> {
                legStatus = "MODERATE";
                confidence = 0.6;
                evidence.add("Primary condition (" + primary + ") appears to be a strong claim in this "
                        + "analysis but is not yet confirmed service-connected — likely, not yet granted. "
                        + "This secondary claim's in-service requirement is met through it once the primary "
                        + "is service-connected (38 CFR 3.310)");
            }
            default -> {
                legStatus = "MISSING";
                confidence = 0.2;
                evidence.add("Confirm your " + primary + " is VA service-connected and its rating — "
                        + "this secondary claim depends on it (38 CFR 3.310). A secondary claim has no "
                        + "separate in-service event; the in-service requirement is met through the "
                        + "service-connected primary");
            }
        }

        // Preserve any pre-existing bullet that documents a REAL primary/secondary
        // link; drop only the stale fake-in-service / direct-connection asks.
        if (existing != null) {
            Object prior = existing.get("evidence");
            if (prior instanceof List<?> l) {
                for (Object item : l) {
                    if (item == null) continue;
                    String bullet = item.toString();
                    if (isStaleInServiceAsk(bullet)) continue;
                    if (!evidence.contains(bullet)) evidence.add(bullet);
                }
            }
        }

        leg.put("status", legStatus);
        leg.put("evidence", evidence);
        leg.put("confidence", confidence);
        leg.put("reasoning", "Secondary claim (38 CFR 3.310): in-service requirement is satisfied through "
                + "the service-connected primary (" + primary + "), not a separate in-service event. "
                + "Primary service-connection status: " + status.name().toLowerCase() + ".");
        return leg;
    }

    /**
     * Surface the transitive dependency on the primary's SC status in the
     * secondary's {@code ratingRationale} (append-only; never fabricated — only set
     * when {@code secondaryTo} is present). The rating NUMBER is untouched.
     */
    private void appendSecondaryDependencyNote(IdentifiedCondition condition, String primary,
                                                PrimaryScStatus status) {
        String note;
        switch (status) {
            case KNOWN -> note = "Secondary to " + primary + " (38 CFR 3.310): readiness depends on the "
                    + "primary's service connection, which the evidence indicates is established.";
            case LIKELY -> note = "Secondary to " + primary + " (38 CFR 3.310): readiness depends on the "
                    + "primary being service-connected — it looks like a strong claim here but is not yet granted.";
            default -> note = "Secondary to " + primary + " (38 CFR 3.310): readiness depends on confirming "
                    + "the primary is VA service-connected and its rating.";
        }
        String existing = condition.getRatingRationale();
        if (existing == null || existing.isBlank()) {
            condition.setRatingRationale(note);
        } else if (!existing.contains(note)) {
            condition.setRatingRationale(existing.strip() + "\n\n" + note);
        }
    }

    // ---- secondary-aware helpers (deterministic, null-safe) ----

    /** A pre-existing in-service bullet that is a stale fake-in-service / direct-connection ask. */
    private boolean isStaleInServiceAsk(String bullet) {
        String lower = bullet.toLowerCase();
        return lower.contains("in-service event")
                || lower.contains("in service event")
                || lower.contains("service treatment record")
                || lower.contains("str ")
                || lower.contains("event, injury, or illness")
                || lower.contains("direct service connection")
                || lower.contains("direct service-connection")
                || lower.contains("no in-service")
                || lower.contains("no documented in-service");
    }

    /** True when the atom text mentions the primary condition (full phrase or its significant tokens). */
    private boolean mentionsPrimary(String textLower, String primaryLower, List<String> primaryTokens) {
        if (textLower.contains(primaryLower)) return true;
        if (primaryTokens.isEmpty()) return false;
        for (String tok : primaryTokens) {
            if (textLower.contains(tok)) return true;
        }
        return false;
    }

    /** True when the (lowercased) atom text states VA service connection / a rating. */
    private boolean statesServiceConnected(String textLower) {
        return textLower.contains("service connected")
                || textLower.contains("service-connected")
                || textLower.contains("service connection")
                || textLower.contains("service-connection")
                || textLower.contains("rated at")
                || textLower.contains("% rating")
                || textLower.contains("percent rating")
                || textLower.contains("disability rating")
                || textLower.matches(".*\\b\\d{1,3}\\s?%.*");
    }

    /**
     * Fuzzy condition-name match: case-insensitive, punctuation/parenthetical
     * stripped, then EITHER exact normalized equality OR a shared significant token
     * (e.g. "PTSD" ↔ "PTSD (Post-Traumatic Stress Disorder)", "GERD" ↔
     * "GERD (Gastroesophageal Reflux Disease)"). Short (≤2 char) tokens and common
     * stopwords are ignored so unrelated conditions don't collide on "and"/"of".
     */
    boolean namesMatchFuzzy(String a, String b) {
        if (a == null || b == null) return false;
        String na = normalizeName(a);
        String nb = normalizeName(b);
        if (na.isEmpty() || nb.isEmpty()) return false;
        if (na.equals(nb)) return true;
        if (na.contains(nb) || nb.contains(na)) return true;
        List<String> ta = significantTokens(a);
        List<String> tb = significantTokens(b);
        for (String t : ta) {
            if (tb.contains(t)) return true;
        }
        return false;
    }

    private String normalizeName(String s) {
        return s.toLowerCase().replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").strip();
    }

    private static final Set<String> NAME_STOPWORDS = Set.of(
            "and", "the", "of", "with", "disorder", "disease", "condition", "syndrome", "chronic", "post");

    /** Significant (length &gt; 2, non-stopword) lowercased tokens of a condition name. */
    private List<String> significantTokens(String s) {
        List<String> out = new ArrayList<>();
        for (String tok : normalizeName(s).split(" ")) {
            if (tok.length() > 2 && !NAME_STOPWORDS.contains(tok)) out.add(tok);
        }
        return out;
    }

    /**
     * True when an in-claim primary condition is itself a STRONG direct claim: its
     * diagnosis leg is STRONG/MODERATE and it is NOT itself an unresolved secondary
     * with an UNKNOWN primary. Presumptive primaries count as strong.
     */
    private boolean isStrongDirectClaim(IdentifiedCondition c) {
        if (Boolean.TRUE.equals(c.getIsPresumptive())) return true;
        String diag = legStatus(c.getTriadDiagnosis());
        String nexus = legStatus(c.getTriadNexus());
        boolean strongDiagnosis = "STRONG".equals(diag) || "MODERATE".equals(diag);
        boolean strongNexus = "STRONG".equals(nexus) || "MODERATE".equals(nexus);
        return strongDiagnosis && strongNexus;
    }

    private String legStatus(Map<String, Object> leg) {
        if (leg == null) return null;
        Object s = leg.get("status");
        return s == null ? null : s.toString().toUpperCase();
    }

    /** Trim to null: blank/whitespace secondary_to values become a clean null. */
    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.strip();
    }

    // =====================================================================
    // Pyramiding grouped view — deterministic §4.14 / §4.130 grouping.
    // =====================================================================

    /**
     * Deterministic pyramiding-group assignment (owner-approved), mirroring
     * {@link #reconcilePresumptiveFromEvidence} / {@link #reconcileSecondary} /
     * {@link #assessRatingEvidence} in shape.
     *
     * <p>Veterans see conditions VA rates TOGETHER (e.g. PTSD, MDD, anxiety — all
     * §4.130 mental-health) as flat separate rows and assume the ratings add. They
     * don't: under one shared rating formula the strongest picture sets the rating and
     * the others are absorbed (§4.14 anti-pyramiding). The LLM-set {@code pyramidGroup}
     * is unreliable, so this pass assigns the group DETERMINISTICALLY from the curated
     * {@link PyramidingGroups} code map:
     * <ul>
     *   <li>For each condition whose VASRD code maps to a canonical group, set
     *       {@code pyramidGroup} to that group label.</li>
     *   <li>Within each group, the EFFECTIVE (counting) member is the single highest
     *       {@code estimatedRating} (ties broken by lowest id for stability). It is
     *       marked {@code pyramidPrimary=true}; the others get a plain-language
     *       {@code pyramidReason} ("Rated together … the strongest (<primary>, N%) sets
     *       the rating; these don't add") — the SAME conditions {@link PyramidingRules}
     *       then excludes from the combined math (its exclusion keys off a non-blank
     *       {@code pyramidReason}), so grouping + math stay consistent and nothing is
     *       double-counted.</li>
     *   <li>Every member (primary + absorbed) is stamped with the group's effective
     *       rating ({@code pyramidGroupRating} = the primary's rating) so the web shows
     *       one combined line without re-deriving it.</li>
     *   <li>TBI (8045) is NOT merged — when a mental-health group exists, its
     *       overlap note is appended to TBI's {@code ratingRationale} (advisory only;
     *       its physical/cognitive residuals stay separately rated). See
     *       {@link PyramidingGroups} for the conservative rationale.</li>
     * </ul>
     * The estimated rating NUMBER is never changed. A single ungrouped member (a group
     * of one) gets no reason/marker — there is nothing to rate "together" with.
     *
     * <p>Flag-gated by the caller ({@code SynthesisStateMachine},
     * {@code va-claim.analysis.pyramiding-groups}, default ON). No-op (returns 0) on a
     * null/empty list.
     *
     * @return the number of conditions assigned to a pyramiding group.
     */
    public int assignPyramidingGroups(List<IdentifiedCondition> conditions) {
        if (conditions == null || conditions.isEmpty()) return 0;

        // (a) Bucket the mapped conditions by their canonical group label. Order is
        // preserved (LinkedHashMap) only for deterministic logging; primary selection
        // is by rating, not order.
        Map<String, List<IdentifiedCondition>> byGroup = new LinkedHashMap<>();
        for (IdentifiedCondition c : conditions) {
            pyramidingGroups.groupFor(c.getVasrdCode())
                    .ifPresent(group -> byGroup.computeIfAbsent(group, k -> new ArrayList<>()).add(c));
        }

        int assigned = 0;
        for (Map.Entry<String, List<IdentifiedCondition>> entry : byGroup.entrySet()) {
            String group = entry.getKey();
            List<IdentifiedCondition> members = entry.getValue();

            // (b) Effective/primary member = highest estimatedRating; ties → lowest id
            // (stable, deterministic). Null ratings sort as 0 (an unrated condition can't
            // be the strongest over a rated peer, but a group of only-null still yields one).
            IdentifiedCondition primary = members.stream()
                    .max(Comparator
                            .comparingInt((IdentifiedCondition c) -> ratingOrZero(c.getEstimatedRating()))
                            .thenComparing(c -> c.getId() == null ? Long.MAX_VALUE : c.getId(),
                                    Comparator.reverseOrder()))
                    .orElse(null);
            if (primary == null) continue;

            int groupRating = ratingOrZero(primary.getEstimatedRating());
            String primaryName = primary.getName() == null ? "the strongest condition" : primary.getName();

            for (IdentifiedCondition c : members) {
                c.setPyramidGroup(group);
                // Group rating stamped on EVERY member (primary + absorbed) so the web
                // shows the single combined result on any row.
                c.setPyramidGroupRating(groupRating);
                if (c == primary) {
                    c.setPyramidPrimary(true);
                    // The effective member is the one PyramidingRules KEEPS — it must NOT
                    // carry a pyramidReason (that is the exclusion signal). Clear any stale
                    // LLM-set reason on it defensively.
                    c.setPyramidReason(null);
                } else {
                    c.setPyramidPrimary(false);
                    c.setPyramidReason(String.format(
                            "Rated together with your %s conditions under one VA formula (§4.130) — "
                                    + "the strongest (%s, %d%%) sets the rating; these don't add.",
                            group, primaryName, groupRating));
                }
                assigned++;
            }

            // (c) TBI overlap note — advisory only, never absorbs TBI. Only for the
            // mental-health group (the one TBI's emotional/behavioral facet overlaps),
            // and only when a TBI condition co-exists in this analysis.
            if (PyramidingGroups.MENTAL_HEALTH_GROUP.equals(group)) {
                applyTbiOverlapNote(conditions, primaryName);
            }

            log.info("[synthesis] pyramiding group '{}' — {} member(s), primary '{}' ({}%)",
                    group, members.size(), primaryName, groupRating);
        }
        return assigned;
    }

    /**
     * Append the TBI overlap note to any TBI (8045) condition's rating rationale when a
     * mental-health group is present. TBI is NEVER placed in the group or given a
     * {@code pyramidReason} — its separate physical/cognitive residuals must stay
     * independently rated (see {@link PyramidingGroups}). Append-only, idempotent.
     */
    private void applyTbiOverlapNote(List<IdentifiedCondition> conditions, String mentalPrimaryName) {
        String note = pyramidingGroups.overlapNote(mentalPrimaryName);
        for (IdentifiedCondition c : conditions) {
            if (!pyramidingGroups.isTbiOverlap(c.getVasrdCode())) continue;
            String existing = c.getRatingRationale();
            if (existing == null || existing.isBlank()) {
                c.setRatingRationale(note);
            } else if (!existing.contains(note)) {
                c.setRatingRationale(existing.strip() + "\n\n" + note);
            }
        }
    }

    /** A condition's rating as a non-null int for comparison (null/absent → 0). */
    private static int ratingOrZero(Integer rating) {
        return rating == null ? 0 : rating;
    }
}
