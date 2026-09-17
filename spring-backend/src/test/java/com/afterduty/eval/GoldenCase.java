package com.afterduty.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * In-memory mirror of a golden {@code case.json} (spec §1.3). Jackson-deserialized
 * from the classpath by {@link GoldenCaseLoader}.
 *
 * <p>Conventions:
 * <ul>
 *   <li>{@code phases} are ordered; the first is the {@code initial} full run, any
 *       further phases are incremental deltas that re-upload only their own docs.</li>
 *   <li>{@code canned} holds the offline-tier responses, keyed by purpose. The
 *       {@code *_by_vasrd} maps fan a purpose out per condition; a {@code null}
 *       value for a DC means "deterministic-rating DC — no canned rate, the value
 *       comes from {@link com.afterduty.service.synthesis.VasrdDecisionEngine}".</li>
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GoldenCase(
        String id,
        String slug,
        String title,
        List<String> tags,
        String status,
        @JsonProperty("needs_expert_review") Boolean needsExpertReview,
        @JsonProperty("expert_review") ExpertReview expertReview,
        List<Phase> phases,
        Canned canned
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExpertReview(String status, String reviewer, String date, String notes) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Phase(String name, List<Doc> docs) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Doc(
            String file,
            String filename,
            @JsonProperty("canned_extraction") String cannedExtraction
    ) {}

    /**
     * Offline-tier canned responses, purpose-keyed. The {@code *_by_vasrd} maps may
     * carry {@code null} values (a DC with no canned rate ⇒ deterministic engine).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Canned(
            @JsonProperty("synthesis_identify") String synthesisIdentify,
            @JsonProperty("synthesis_duplicate_merger") String synthesisDuplicateMerger,
            @JsonProperty("synthesis_rate_by_vasrd") Map<String, String> synthesisRateByVasrd,
            @JsonProperty("synthesis_verify") String synthesisVerify,
            @JsonProperty("gap_evidence_by_vasrd") Map<String, String> gapEvidenceByVasrd,
            @JsonProperty("gap_validation_by_vasrd") Map<String, String> gapValidationByVasrd,
            @JsonProperty("gap_whatif_by_vasrd") Map<String, String> gapWhatifByVasrd
    ) {}

    /** True iff this case declares the named phase. */
    public boolean hasPhase(String phaseName) {
        return phases != null && phases.stream().anyMatch(p -> phaseName.equals(p.name()));
    }

    public boolean isActive() {
        return "active".equals(status);
    }
}
