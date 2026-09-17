package com.afterduty.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * In-memory mirror of a golden {@code expected.json} (spec §1.4). Expectations are
 * per phase, keyed by phase name, because the delta phases are where
 * carry-forward / dirty-scope correctness lives.
 *
 * <p>Shared by BOTH tiers: in the offline tier {@code rating_band} verifies plumbing
 * of canned values; in the live tier the same field scores real model output.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GoldenExpectation(
        @JsonProperty("case_id") String caseId,
        Map<String, PhaseExpectation> phases
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PhaseExpectation(
            @JsonProperty("pipeline_outcome") String pipelineOutcome,
            List<ConditionExpectation> conditions,
            @JsonProperty("forbidden_conditions") List<ForbiddenCondition> forbiddenConditions,
            @JsonProperty("combined_rating_band") Band combinedRatingBand,
            List<GapExpectation> gaps,
            List<PyramidingExpectation> pyramiding,
            Abstention abstention,
            // per-evidence processing-state assertions (spec §2.4.1 — the only thing
            // that distinguishes honest abstention-on-unreadable from a silent zero):
            @JsonProperty("expected_evidence") List<EvidenceExpectation> expectedEvidence,
            // delta-phase only:
            @JsonProperty("dirty_conditions_max") Integer dirtyConditionsMax,
            @JsonProperty("carry_forward_min") Integer carryForwardMin,
            @JsonProperty("synthesis_jobs_max") Integer synthesisJobsMax
    ) {}

    /**
     * Asserts a phase's evidence processing state (spec §2.4.1). For the unreadable-scan
     * case (gc-013) this pins {@code processing_status=error} with a non-null, non-blank
     * plain-language {@code processing_message} — the regression that would otherwise mark
     * an unreadable doc 'processed' with empty atoms (silent zero) instead of 'error'.
     *
     * @param filenamePattern  case-insensitive Java regex matched against the evidence
     *                         filename to select WHICH evidence row this expectation
     *                         applies to (null ⇒ applies to every evidence row in the phase)
     * @param processingStatus required {@link com.afterduty.model.EvidenceItem#getProcessingStatus()}
     * @param messageNonBlank  when true, the matched evidence's processing_message must be
     *                         non-null and non-blank (the plain-language explanation)
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EvidenceExpectation(
            @JsonProperty("filename_pattern") String filenamePattern,
            @JsonProperty("processing_status") String processingStatus,
            @JsonProperty("message_non_blank") Boolean messageNonBlank
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ConditionExpectation(
            @JsonProperty("name_pattern") String namePattern,
            @JsonProperty("vasrd_code") String vasrdCode,
            @JsonProperty("body_system") String bodySystem,
            String laterality,
            @JsonProperty("rating_band") Band ratingBand,
            Boolean presumptive,
            @JsonProperty("must_be_found") Boolean mustBeFound,
            @JsonProperty("deterministic_rating_expected") Boolean deterministicRatingExpected
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ForbiddenCondition(
            @JsonProperty("vasrd_code") String vasrdCode,
            String reason
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Band(Integer min, Integer max) {
        public boolean contains(int value) {
            return value >= min && value <= max;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GapExpectation(
            @JsonProperty("condition_vasrd") String conditionVasrd,
            String pattern,
            @JsonProperty("must_be_flagged") Boolean mustBeFlagged
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PyramidingExpectation(
            @JsonProperty("vasrd_code") String vasrdCode,
            @JsonProperty("max_rating") Integer maxRating,
            @JsonProperty("absorbed") Boolean absorbed,
            @JsonProperty("bilateral_pair") Boolean bilateralPair
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Abstention(Boolean expected) {}
}
