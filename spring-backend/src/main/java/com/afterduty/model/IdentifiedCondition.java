package com.afterduty.model;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@Entity
@Table(name = "identified_conditions")
public class IdentifiedCondition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "claim_id", nullable = false)
    private Long claimId;

    @Column(nullable = false)
    private String name;

    @Column(name = "vasrd_code")
    private String vasrdCode;

    @Column(name = "body_system")
    private String bodySystem;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "triad_diagnosis", columnDefinition = "text")
    private Map<String, Object> triadDiagnosis;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "triad_in_service", columnDefinition = "text")
    private Map<String, Object> triadInService;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "triad_nexus", columnDefinition = "text")
    private Map<String, Object> triadNexus;

    @Column(name = "is_presumptive")
    private Boolean isPresumptive = false;

    @Column(name = "presumptive_basis")
    private String presumptiveBasis;

    /**
     * Secondary-aware analysis (38 CFR 3.310). When set (non-blank), THIS condition
     * is claimed as SECONDARY to the named PRIMARY, service-connected condition —
     * e.g. GERD secondary to PTSD holds {@code "PTSD (Post-Traumatic Stress
     * Disorder)"}. A secondary claim has NO separate in-service event: the in-service
     * requirement is satisfied THROUGH the service-connected primary, so the
     * "in-service" triad leg is reframed to "the primary is service-connected"
     * (see {@code EnhancedSynthesisOrchestrator.reconcileSecondary}). Null on direct
     * claims and on every legacy row — a null/blank value is exactly today's
     * direct-service-connection behavior. Declared as a plain String column, matching
     * {@code presumptiveBasis} (ddl-auto adds the column; no migration framework).
     */
    @Column(name = "secondary_to")
    private String secondaryTo;

    @Column(name = "estimated_rating")
    private Integer estimatedRating = 0;

    @Column(name = "rating_rationale", columnDefinition = "text")
    private String ratingRationale;

    /**
     * Rating honesty (owner-approved). Set by the deterministic
     * {@code EnhancedSynthesisOrchestrator.assessRatingEvidence} post-pass when this
     * condition's VASRD code is in the curated objective-evidence map AND the measure
     * its rating tiers hinge on is genuinely ABSENT from the evidence — e.g. asthma 6602
     * with no PFT/FEV-1 on file gets "Estimate — needs a pulmonary function test
     * (PFT/FEV-1) to confirm…". Null when the code is unmapped OR the measure is present
     * (an authoritative-looking rating carries no needless caveat). The estimated rating
     * NUMBER is never changed by that pass — only the served {@code confidence} is
     * tempered alongside this note. Declared as a plain nullable String column, matching
     * {@code presumptiveBasis} / {@code secondaryTo} (ddl-auto adds the column; no
     * migration framework). Null on every legacy row / flag-OFF run.
     */
    @Column(name = "rating_evidence_note", columnDefinition = "text")
    private String ratingEvidenceNote;

    private Double confidence = 0.0;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "text")
    private List<Map<String, Object>> gaps;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "what_if_scenarios", columnDefinition = "text")
    private List<Map<String, Object>> whatIfScenarios;

    @Column(name = "superseded_by")
    private Long supersededBy;

    /**
     * Mission 5b — stable condition identity across re-analysis generations.
     * SHA-256 of the normalized (vasrd code | body system/category | connection
     * theory | laterality) tuple. Laterality (bilateral / left / right / a
     * dual-site pairing) is parsed from the condition name so two same-DC
     * bilateral or per-side conditions never collapse into one — the judges
     * flagged that exact mis-merge risk. Used to link an old generation's
     * condition to its replacement in the new generation (supersededBy points the
     * old row at the new row with the matching identity), so user-facing
     * references (chat citations, scenario condition ids, user-set gap statuses)
     * survive a re-analysis. Null on legacy rows and on rows written with the
     * incremental flag OFF (carry-forward/supersede are then no-ops).
     */
    @Column(name = "identity_fingerprint")
    private String identityFingerprint;

    /**
     * Mission 5b — dirty-scope carry-forward. SHA-256 of the sorted live atom
     * ids/values that feed this condition's rating prompt plus the prompt/model
     * versions in effect for the run. A new-generation condition whose identity
     * fingerprint AND evidence fingerprint both equal a prior completed active
     * condition's is CLEAN: its rating/verification/gap outputs are copied
     * forward and NO rate/gap LLM jobs are submitted for it. Only DIRTY
     * conditions (new evidence, new identity, or a prompt/model version bump that
     * shifts this hash) fan out. Null with the flag OFF.
     */
    @Column(name = "evidence_fingerprint")
    private String evidenceFingerprint;

    /**
     * Phase B item B2 — attribution at identify. The live atom ids the identify
     * model cited as supporting THIS condition ({@code supporting_atom_ids} in the
     * identify response, unioned across merged duplicates and filtered to ids that
     * actually exist in the run's live corpus). Written on every run regardless of
     * flags (it is pure data); read by the per-condition evidence fingerprint when
     * {@code va-claim.synthesis.per-condition-fingerprint} is ON. Null = the model
     * attributed nothing usable (legacy rows, hallucinated ids, merger renames) —
     * the scoped fingerprint then falls back to the corpus-wide hash (safety valve:
     * an unattributed condition dirties whenever ANY atom changes).
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "supporting_atom_ids", columnDefinition = "text")
    private List<Long> supportingAtomIds;

    /**
     * Phase B item B2 — the corpus-wide evidence fingerprint of the run that wrote
     * this row (what {@code evidence_fingerprint} held before per-condition scoping
     * existed). All rows of one generation share it. The scheduler's no-new-facts
     * short-circuit compares against THIS column (falling back to
     * {@code evidence_fingerprint} for legacy rows), so scoping the per-condition
     * fingerprint never breaks duplicate-upload skip detection.
     */
    @Column(name = "corpus_fingerprint")
    private String corpusFingerprint;

    /**
     * Phase B item B2 — when this condition's expensive outputs (rate/verify/gap)
     * were last computed by a FULL LLM pass rather than carried forward. Stamped at
     * classification time on DIRTY conditions; copied from the prior row on clean
     * carry-forward. The 30-day full-refresh safety valve forces a condition DIRTY
     * when this is older than {@code va-claim.synthesis.full-refresh-days} (or null)
     * under per-condition fingerprints.
     */
    @Column(name = "last_full_run_at")
    private java.time.Instant lastFullRunAt;

    @Column(name = "pyramid_group")
    private String pyramidGroup;

    @Column(name = "pyramid_reason", columnDefinition = "text")
    private String pyramidReason;

    /**
     * Pyramiding grouped view — the EFFECTIVE (counting) member of a pyramiding
     * group. Set by the deterministic {@code assignPyramidingGroups} pass to the
     * single highest-{@code estimatedRating} condition within each canonical group
     * (the one PyramidingRules keeps in the combined math; the others are absorbed
     * and carry a {@code pyramidReason}). The web reads this to render "the strongest
     * counts" and to know which member's rating is the group's effective result.
     * Null on every non-grouped condition, on legacy rows, and on flag-OFF runs.
     * Declared as a plain nullable column, matching {@code pyramidGroup} (ddl-auto
     * adds it; no migration framework).
     */
    @Column(name = "pyramid_primary")
    private Boolean pyramidPrimary;

    /**
     * Pyramiding grouped view — the group's EFFECTIVE rating (the effective/primary
     * member's {@code estimatedRating}). Stamped on EVERY member of a group (primary
     * and absorbed alike) by {@code assignPyramidingGroups} so the web can show the
     * single combined result ("These combine to ~70% — the strongest counts, they
     * don't add") without re-deriving it. This is NOT a new math source — it equals
     * the primary member's own rating, kept consistent with PyramidingRules, which
     * excludes the absorbed members from the combined calculation. Null when this
     * condition is not in a group / legacy / flag-OFF.
     */
    @Column(name = "pyramid_group_rating")
    private Integer pyramidGroupRating;

    /**
     * "Don't include in my claim" (owner-set, reversible). When TRUE the veteran
     * has told us this condition is VALID but they are NOT filing for it (e.g. a
     * TDIU line, or a condition they'd rather leave out) — it must DROP OUT of the
     * combined-rating math (RatingController filters it alongside the rating&gt;0
     * filter) while STAYING visible in the conditions list (the web moves it into a
     * collapsible "Not filing" section, one tap to re-include). This is DISTINCT
     * from delete/suppress ("this is wrong", ConditionSuppression) — it is a plain
     * reversible boolean the veteran flips, not a correctness judgment. Defaults
     * FALSE; null on every legacy row reads as "included" (Boolean.TRUE.equals
     * guards). Declared as a plain nullable column, matching {@code pyramidPrimary}
     * (ddl-auto adds it; no migration framework).
     */
    @Column(name = "excluded_from_claim")
    private Boolean excludedFromClaim = false;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "claim_id", insertable = false, updatable = false)
    private Claim claim;

    public IdentifiedCondition() {
    }

    public IdentifiedCondition(Long id, Long claimId, String name, String vasrdCode, String bodySystem,
                               Map<String, Object> triadDiagnosis, Map<String, Object> triadInService,
                               Map<String, Object> triadNexus, Boolean isPresumptive, String presumptiveBasis,
                               Integer estimatedRating, String ratingRationale, Double confidence,
                               List<Map<String, Object>> gaps, List<Map<String, Object>> whatIfScenarios, Claim claim) {
        this.id = id;
        this.claimId = claimId;
        this.name = name;
        this.vasrdCode = vasrdCode;
        this.bodySystem = bodySystem;
        this.triadDiagnosis = triadDiagnosis;
        this.triadInService = triadInService;
        this.triadNexus = triadNexus;
        this.isPresumptive = isPresumptive;
        this.presumptiveBasis = presumptiveBasis;
        this.estimatedRating = estimatedRating;
        this.ratingRationale = ratingRationale;
        this.confidence = confidence;
        this.gaps = gaps;
        this.whatIfScenarios = whatIfScenarios;
        this.claim = claim;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getClaimId() {
        return claimId;
    }

    public void setClaimId(Long claimId) {
        this.claimId = claimId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getVasrdCode() {
        return vasrdCode;
    }

    public void setVasrdCode(String vasrdCode) {
        this.vasrdCode = vasrdCode;
    }

    public String getBodySystem() {
        return bodySystem;
    }

    public void setBodySystem(String bodySystem) {
        this.bodySystem = bodySystem;
    }

    public Map<String, Object> getTriadDiagnosis() {
        return triadDiagnosis;
    }

    public void setTriadDiagnosis(Map<String, Object> triadDiagnosis) {
        this.triadDiagnosis = triadDiagnosis;
    }

    public Map<String, Object> getTriadInService() {
        return triadInService;
    }

    public void setTriadInService(Map<String, Object> triadInService) {
        this.triadInService = triadInService;
    }

    public Map<String, Object> getTriadNexus() {
        return triadNexus;
    }

    public void setTriadNexus(Map<String, Object> triadNexus) {
        this.triadNexus = triadNexus;
    }

    public Boolean getIsPresumptive() {
        return isPresumptive;
    }

    public void setIsPresumptive(Boolean isPresumptive) {
        this.isPresumptive = isPresumptive;
    }

    public String getPresumptiveBasis() {
        return presumptiveBasis;
    }

    public void setPresumptiveBasis(String presumptiveBasis) {
        this.presumptiveBasis = presumptiveBasis;
    }

    public String getSecondaryTo() {
        return secondaryTo;
    }

    public void setSecondaryTo(String secondaryTo) {
        this.secondaryTo = secondaryTo;
    }

    public Integer getEstimatedRating() {
        return estimatedRating;
    }

    public void setEstimatedRating(Integer estimatedRating) {
        this.estimatedRating = estimatedRating;
    }

    public String getRatingRationale() {
        return ratingRationale;
    }

    public void setRatingRationale(String ratingRationale) {
        this.ratingRationale = ratingRationale;
    }

    public Double getConfidence() {
        return confidence;
    }

    public void setConfidence(Double confidence) {
        this.confidence = confidence;
    }

    public List<Map<String, Object>> getGaps() {
        return gaps;
    }

    public void setGaps(List<Map<String, Object>> gaps) {
        this.gaps = gaps;
    }

    public List<Map<String, Object>> getWhatIfScenarios() {
        return whatIfScenarios;
    }

    public void setWhatIfScenarios(List<Map<String, Object>> whatIfScenarios) {
        this.whatIfScenarios = whatIfScenarios;
    }

    public Claim getClaim() {
        return claim;
    }

    public void setClaim(Claim claim) {
        this.claim = claim;
    }

    public Long getSupersededBy() { return supersededBy; }
    public void setSupersededBy(Long supersededBy) { this.supersededBy = supersededBy; }

    public String getIdentityFingerprint() { return identityFingerprint; }
    public void setIdentityFingerprint(String identityFingerprint) { this.identityFingerprint = identityFingerprint; }

    public String getEvidenceFingerprint() { return evidenceFingerprint; }
    public void setEvidenceFingerprint(String evidenceFingerprint) { this.evidenceFingerprint = evidenceFingerprint; }

    public List<Long> getSupportingAtomIds() { return supportingAtomIds; }
    public void setSupportingAtomIds(List<Long> supportingAtomIds) { this.supportingAtomIds = supportingAtomIds; }

    public String getCorpusFingerprint() { return corpusFingerprint; }
    public void setCorpusFingerprint(String corpusFingerprint) { this.corpusFingerprint = corpusFingerprint; }

    public java.time.Instant getLastFullRunAt() { return lastFullRunAt; }
    public void setLastFullRunAt(java.time.Instant lastFullRunAt) { this.lastFullRunAt = lastFullRunAt; }

    public String getPyramidGroup() { return pyramidGroup; }
    public void setPyramidGroup(String pyramidGroup) { this.pyramidGroup = pyramidGroup; }

    public String getPyramidReason() { return pyramidReason; }
    public void setPyramidReason(String pyramidReason) { this.pyramidReason = pyramidReason; }

    public Boolean getPyramidPrimary() { return pyramidPrimary; }
    public void setPyramidPrimary(Boolean pyramidPrimary) { this.pyramidPrimary = pyramidPrimary; }

    public Integer getPyramidGroupRating() { return pyramidGroupRating; }
    public void setPyramidGroupRating(Integer pyramidGroupRating) { this.pyramidGroupRating = pyramidGroupRating; }

    public Boolean getExcludedFromClaim() { return excludedFromClaim; }
    public void setExcludedFromClaim(Boolean excludedFromClaim) { this.excludedFromClaim = excludedFromClaim; }

    public String getRatingEvidenceNote() { return ratingEvidenceNote; }
    public void setRatingEvidenceNote(String ratingEvidenceNote) { this.ratingEvidenceNote = ratingEvidenceNote; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        IdentifiedCondition that = (IdentifiedCondition) o;
        return Objects.equals(id, that.id) &&
                Objects.equals(claimId, that.claimId) &&
                Objects.equals(name, that.name) &&
                Objects.equals(vasrdCode, that.vasrdCode) &&
                Objects.equals(bodySystem, that.bodySystem) &&
                Objects.equals(triadDiagnosis, that.triadDiagnosis) &&
                Objects.equals(triadInService, that.triadInService) &&
                Objects.equals(triadNexus, that.triadNexus) &&
                Objects.equals(isPresumptive, that.isPresumptive) &&
                Objects.equals(presumptiveBasis, that.presumptiveBasis) &&
                Objects.equals(estimatedRating, that.estimatedRating) &&
                Objects.equals(ratingRationale, that.ratingRationale) &&
                Objects.equals(confidence, that.confidence) &&
                Objects.equals(gaps, that.gaps) &&
                Objects.equals(whatIfScenarios, that.whatIfScenarios);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, claimId, name, vasrdCode, bodySystem, triadDiagnosis, triadInService, triadNexus, isPresumptive, presumptiveBasis, estimatedRating, ratingRationale, confidence, gaps, whatIfScenarios);
    }

    @Override
    public String toString() {
        return "IdentifiedCondition(" +
                "id=" + id +
                ", claimId=" + claimId +
                ", name=" + name +
                ", vasrdCode=" + vasrdCode +
                ", bodySystem=" + bodySystem +
                ", triadDiagnosis=" + triadDiagnosis +
                ", triadInService=" + triadInService +
                ", triadNexus=" + triadNexus +
                ", isPresumptive=" + isPresumptive +
                ", presumptiveBasis=" + presumptiveBasis +
                ", estimatedRating=" + estimatedRating +
                ", ratingRationale=" + ratingRationale +
                ", confidence=" + confidence +
                ", gaps=" + gaps +
                ", whatIfScenarios=" + whatIfScenarios +
                ')';
    }

    public static IdentifiedConditionBuilder builder() {
        return new IdentifiedConditionBuilder();
    }

    public static class IdentifiedConditionBuilder {
        private Long id;
        private Long claimId;
        private String name;
        private String vasrdCode;
        private String bodySystem;
        private Map<String, Object> triadDiagnosis;
        private Map<String, Object> triadInService;
        private Map<String, Object> triadNexus;
        private Boolean isPresumptive = false;
        private String presumptiveBasis;
        private String secondaryTo;
        private Integer estimatedRating = 0;
        private String ratingRationale;
        private String ratingEvidenceNote;
        private Boolean excludedFromClaim = false;
        private Double confidence = 0.0;
        private List<Map<String, Object>> gaps;
        private List<Map<String, Object>> whatIfScenarios;
        private Claim claim;

        IdentifiedConditionBuilder() {
        }

        public IdentifiedConditionBuilder id(Long id) {
            this.id = id;
            return this;
        }

        public IdentifiedConditionBuilder claimId(Long claimId) {
            this.claimId = claimId;
            return this;
        }

        public IdentifiedConditionBuilder name(String name) {
            this.name = name;
            return this;
        }

        public IdentifiedConditionBuilder vasrdCode(String vasrdCode) {
            this.vasrdCode = vasrdCode;
            return this;
        }

        public IdentifiedConditionBuilder bodySystem(String bodySystem) {
            this.bodySystem = bodySystem;
            return this;
        }

        public IdentifiedConditionBuilder triadDiagnosis(Map<String, Object> triadDiagnosis) {
            this.triadDiagnosis = triadDiagnosis;
            return this;
        }

        public IdentifiedConditionBuilder triadInService(Map<String, Object> triadInService) {
            this.triadInService = triadInService;
            return this;
        }

        public IdentifiedConditionBuilder triadNexus(Map<String, Object> triadNexus) {
            this.triadNexus = triadNexus;
            return this;
        }

        public IdentifiedConditionBuilder isPresumptive(Boolean isPresumptive) {
            this.isPresumptive = isPresumptive;
            return this;
        }

        public IdentifiedConditionBuilder presumptiveBasis(String presumptiveBasis) {
            this.presumptiveBasis = presumptiveBasis;
            return this;
        }

        public IdentifiedConditionBuilder secondaryTo(String secondaryTo) {
            this.secondaryTo = secondaryTo;
            return this;
        }

        public IdentifiedConditionBuilder estimatedRating(Integer estimatedRating) {
            this.estimatedRating = estimatedRating;
            return this;
        }

        public IdentifiedConditionBuilder ratingRationale(String ratingRationale) {
            this.ratingRationale = ratingRationale;
            return this;
        }

        public IdentifiedConditionBuilder ratingEvidenceNote(String ratingEvidenceNote) {
            this.ratingEvidenceNote = ratingEvidenceNote;
            return this;
        }

        public IdentifiedConditionBuilder excludedFromClaim(Boolean excludedFromClaim) {
            this.excludedFromClaim = excludedFromClaim;
            return this;
        }

        public IdentifiedConditionBuilder confidence(Double confidence) {
            this.confidence = confidence;
            return this;
        }

        public IdentifiedConditionBuilder gaps(List<Map<String, Object>> gaps) {
            this.gaps = gaps;
            return this;
        }

        public IdentifiedConditionBuilder whatIfScenarios(List<Map<String, Object>> whatIfScenarios) {
            this.whatIfScenarios = whatIfScenarios;
            return this;
        }

        public IdentifiedConditionBuilder claim(Claim claim) {
            this.claim = claim;
            return this;
        }

        public IdentifiedCondition build() {
            IdentifiedCondition c = new IdentifiedCondition(id, claimId, name, vasrdCode, bodySystem, triadDiagnosis, triadInService, triadNexus, isPresumptive, presumptiveBasis, estimatedRating, ratingRationale, confidence, gaps, whatIfScenarios, claim);
            c.secondaryTo = secondaryTo;
            c.ratingEvidenceNote = ratingEvidenceNote;
            c.excludedFromClaim = excludedFromClaim;
            return c;
        }
    }
}
