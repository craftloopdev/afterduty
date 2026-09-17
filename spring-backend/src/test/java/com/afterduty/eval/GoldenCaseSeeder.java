package com.afterduty.eval;

import com.afterduty.model.Claim;
import com.afterduty.model.EvidenceItem;
import com.afterduty.model.IdentifiedCondition;
import com.afterduty.model.LlmJob;
import com.afterduty.repository.ClaimRepository;
import com.afterduty.repository.EvidenceItemRepository;
import com.afterduty.repository.IdentifiedConditionRepository;
import com.afterduty.service.llm.FakeLlmAsyncProvider;

import java.util.Map;
import java.util.function.Function;

/**
 * Seeds one golden case into the H2 DB and registers its canned responses on the
 * {@link FakeLlmAsyncProvider} (spec §2.2 seeder responsibilities).
 *
 * <p>Per phase it creates the {@link Claim} (once) and one {@link EvidenceItem} per
 * doc (text path, {@code rawContent} = the synthetic doc body), and binds each
 * evidence to its canned DocFacts via {@link FakeLlmAsyncProvider#setResponseForEvidence}.
 *
 * <p>The purpose-level synthesis responses ({@code synthesis_identify},
 * {@code synthesis_duplicate_merger}, {@code synthesis_verify}) are registered as
 * plain purpose-keyed canned text. The per-condition fan-out purposes
 * ({@code synthesis_rate}, {@code gap_evidence}, {@code gap_validation},
 * {@code gap_whatif}) are registered as RESPONDERS that resolve the job's
 * conditionId → the persisted {@link IdentifiedCondition} → its VASRD code → the
 * case's {@code *_by_vasrd} canned file. A null per-DC entry (deterministic-rating
 * DC) means the rate responder returns the canned text mapped under that DC if any,
 * else falls back to a benign default — see {@link #DETERMINISTIC_RATE_FALLBACK}.
 */
public final class GoldenCaseSeeder {

    /**
     * Rate fallback for a deterministic-rating DC whose {@code *_by_vasrd} entry is
     * null. The committed pipeline ALWAYS submits a synthesis_rate job (it has no
     * VasrdDecisionEngine bypass), so the responder must return something parseable.
     * For deterministic DCs the {@code DeterministicScorer} independently asserts
     * the {@link com.afterduty.service.synthesis.VasrdDecisionEngine} would have
     * produced the expected band — this fallback just keeps the run flowing with a
     * value that lands in the (wide) expected band. It is keyed per case at seed
     * time from the expectation, NOT hard-coded.
     */
    static final String DETERMINISTIC_RATE_FALLBACK =
            "{\"estimated_rating\":%d,\"rating_rationale\":\"deterministic (VasrdDecisionEngine) "
            + "value for DC %s, surfaced via canned rate so the committed always-fan-out rate "
            + "stage stays parseable\",\"confidence\":0.99}";

    private final ClaimRepository claimRepository;
    private final EvidenceItemRepository evidenceItemRepository;
    private final IdentifiedConditionRepository conditionRepository;
    private final FakeLlmAsyncProvider fake;
    private final GoldenCaseLoader loader;

    public GoldenCaseSeeder(ClaimRepository claimRepository,
                            EvidenceItemRepository evidenceItemRepository,
                            IdentifiedConditionRepository conditionRepository,
                            FakeLlmAsyncProvider fake,
                            GoldenCaseLoader loader) {
        this.claimRepository = claimRepository;
        this.evidenceItemRepository = evidenceItemRepository;
        this.conditionRepository = conditionRepository;
        this.fake = fake;
        this.loader = loader;
    }

    /** Create the claim for a case. Call once, before the first phase. */
    public Claim createClaim(long userId) {
        return claimRepository.save(Claim.builder()
                .userId(userId)
                .status(Claim.ClaimStatus.DRAFT)
                .build());
    }

    /**
     * Register all purpose/responder canned responses for the case. Idempotent —
     * safe to call once up front. Per-evidence DocFacts are bound when each phase's
     * evidence is created (ids aren't known until then), so call
     * {@link #seedPhaseEvidence} per phase.
     *
     * @param deterministicRateBands DC → the rating value to surface for a
     *        deterministic-rating DC whose {@code *_by_vasrd} entry is null (from
     *        the case's expectation midpoint)
     */
    public void registerCanned(GoldenCaseLoader.LoadedCase view, Map<String, Integer> deterministicRateBands) {
        GoldenCase c = view.caseFile();
        String base = view.basePath();
        GoldenCase.Canned canned = c.canned();
        if (canned == null) {
            return;
        }

        if (canned.synthesisIdentify() != null) {
            fake.setResponse("synthesis_identify", loader.readText(base, canned.synthesisIdentify()));
        }
        if (canned.synthesisDuplicateMerger() != null) {
            fake.setResponse("synthesis_duplicate_merger", loader.readText(base, canned.synthesisDuplicateMerger()));
        }
        if (canned.synthesisVerify() != null) {
            fake.setResponse("synthesis_verify", loader.readText(base, canned.synthesisVerify()));
        }

        fake.setResponder("synthesis_rate",
                rateResponder(base, canned.synthesisRateByVasrd(), deterministicRateBands));
        fake.setResponder("gap_evidence",
                byVasrdResponder(base, canned.gapEvidenceByVasrd(), "[]"));
        fake.setResponder("gap_validation",
                byVasrdResponder(base, canned.gapValidationByVasrd(), "[]"));
        fake.setResponder("gap_whatif",
                byVasrdResponder(base, canned.gapWhatifByVasrd(), "[]"));
    }

    /**
     * Create the EvidenceItems for one phase and bind each to its canned DocFacts.
     * Returns nothing — the driver discovers the new evidence via the claim.
     */
    public void seedPhaseEvidence(Claim claim, GoldenCaseLoader.LoadedCase view, String phaseName) {
        GoldenCase.Phase phase = view.caseFile().phases().stream()
                .filter(p -> phaseName.equals(p.name()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "case " + view.caseFile().id() + " has no phase " + phaseName));

        for (GoldenCase.Doc doc : phase.docs()) {
            String body = loader.readText(view.basePath(), doc.file());
            EvidenceItem ev = evidenceItemRepository.save(EvidenceItem.builder()
                    .claimId(claim.getId())
                    .sourceType("text")
                    .rawContent(body)
                    .filename(doc.filename())
                    .processingStatus("pending")
                    .build());

            String docFacts = loader.readText(view.basePath(), doc.cannedExtraction());
            fake.setResponseForEvidence(ev.getId(), docFacts);
        }
    }

    // ------------------------------------------------------------------ responders

    /** synthesis_rate responder: conditionId → DC → canned rate, with deterministic fallback. */
    private Function<LlmJob, String> rateResponder(String base, Map<String, String> byVasrd,
                                                   Map<String, Integer> deterministicRateBands) {
        return job -> {
            String dc = resolveDc(job);
            if (dc == null) {
                // No DC (code-less condition) — return a benign 0% so the run flows.
                return "{\"estimated_rating\":0,\"rating_rationale\":\"no DC\",\"confidence\":0.5}";
            }
            if (byVasrd != null && byVasrd.containsKey(dc)) {
                String rel = byVasrd.get(dc);
                if (rel != null) {
                    return loader.readText(base, rel);
                }
                // null ⇒ deterministic-rating DC: surface the engine's value via canned text.
                int value = deterministicRateBands.getOrDefault(dc, 0);
                return String.format(DETERMINISTIC_RATE_FALLBACK, value, dc);
            }
            throw new IllegalStateException("golden case is under-specified: synthesis_rate job for DC '"
                    + dc + "' has no entry in synthesis_rate_by_vasrd");
        };
    }

    /** gap_* responder: conditionId → DC → canned gap text, falling back to an empty array. */
    private Function<LlmJob, String> byVasrdResponder(String base, Map<String, String> byVasrd,
                                                     String fallback) {
        return job -> {
            String dc = resolveDc(job);
            if (byVasrd == null || dc == null) {
                return fallback;
            }
            String rel = byVasrd.get(dc);
            return rel != null ? loader.readText(base, rel) : fallback;
        };
    }

    /** Resolve a fan-out job's conditionId to its VASRD code (or null). */
    private String resolveDc(LlmJob job) {
        Long conditionId = job.getConditionId();
        if (conditionId == null) {
            return null;
        }
        return conditionRepository.findById(conditionId)
                .map(IdentifiedCondition::getVasrdCode)
                .orElse(null);
    }
}
