package com.afterduty.dto;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public class ConditionResponse {
    private Long id;
    private Long claimId;
    private String name;
    private String vasrdCode;
    private String bodySystem;
    private Map<String, Object> triadDiagnosis;
    private Map<String, Object> triadInService;
    private Map<String, Object> triadNexus;
    private boolean isPresumptive;
    private String presumptiveBasis;
    private Integer estimatedRating;
    private String ratingRationale;
    private Double confidence;
    private List<Map<String, Object>> gaps;
    private List<Map<String, Object>> whatIfScenarios;
    private String pyramidGroup;
    private String pyramidReason;
    // Pyramiding grouped view: true on the single EFFECTIVE (counting, highest-rated)
    // member of a pyramiding group — the one whose rating the group combines to. Null on
    // absorbed members and on ungrouped conditions. Lets the web mark "the strongest
    // counts" deterministically instead of re-deriving it from the reason string.
    private Boolean pyramidPrimary;
    // Pyramiding grouped view: the group's effective rating (the primary member's own
    // estimatedRating), stamped on every member so the web shows the single combined line
    // ("~70% — the strongest counts, they don't add") without re-computing. Null when the
    // condition is not in a group.
    private Integer pyramidGroupRating;
    // Secondary-aware analysis: the PRIMARY, service-connected condition this
    // condition is claimed secondary to (38 CFR 3.310), or null for a direct claim.
    private String secondaryTo;
    // Rating honesty: a non-alarming "Estimate — needs <objective measure> to confirm"
    // note set when this condition's code is in the curated objective-evidence map and
    // that measure is absent from the evidence (e.g. asthma 6602 with no PFT/FEV-1).
    // Null when the code is unmapped or the measure is present. The tempered confidence
    // rides the existing `confidence` field.
    private String ratingEvidenceNote;
    // "Don't include in my claim" (owner-set, reversible): TRUE = the veteran isn't
    // filing for this valid condition, so it drops out of the combined-rating math but
    // STAYS in the list (the web moves it to a collapsible "Not filing" section). Null/
    // false = included. Distinct from delete/suppress ("this is wrong"). Additive.
    private Boolean excludedFromClaim;

    public ConditionResponse() {
    }

    public ConditionResponse(Long id, Long claimId, String name, String vasrdCode, String bodySystem,
                             Map<String, Object> triadDiagnosis, Map<String, Object> triadInService,
                             Map<String, Object> triadNexus, boolean isPresumptive, String presumptiveBasis,
                             Integer estimatedRating, String ratingRationale, Double confidence,
                             List<Map<String, Object>> gaps, List<Map<String, Object>> whatIfScenarios) {
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

    public boolean isPresumptive() {
        return isPresumptive;
    }

    public void setPresumptive(boolean presumptive) {
        isPresumptive = presumptive;
    }

    public String getPresumptiveBasis() {
        return presumptiveBasis;
    }

    public void setPresumptiveBasis(String presumptiveBasis) {
        this.presumptiveBasis = presumptiveBasis;
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

    public String getPyramidGroup() { return pyramidGroup; }
    public void setPyramidGroup(String pyramidGroup) { this.pyramidGroup = pyramidGroup; }

    public String getPyramidReason() { return pyramidReason; }
    public void setPyramidReason(String pyramidReason) { this.pyramidReason = pyramidReason; }

    public Boolean getPyramidPrimary() { return pyramidPrimary; }
    public void setPyramidPrimary(Boolean pyramidPrimary) { this.pyramidPrimary = pyramidPrimary; }

    public Integer getPyramidGroupRating() { return pyramidGroupRating; }
    public void setPyramidGroupRating(Integer pyramidGroupRating) { this.pyramidGroupRating = pyramidGroupRating; }

    public String getSecondaryTo() { return secondaryTo; }
    public void setSecondaryTo(String secondaryTo) { this.secondaryTo = secondaryTo; }

    public String getRatingEvidenceNote() { return ratingEvidenceNote; }
    public void setRatingEvidenceNote(String ratingEvidenceNote) { this.ratingEvidenceNote = ratingEvidenceNote; }

    public Boolean getExcludedFromClaim() { return excludedFromClaim; }
    public void setExcludedFromClaim(Boolean excludedFromClaim) { this.excludedFromClaim = excludedFromClaim; }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Long id;
        private Long claimId;
        private String name;
        private String vasrdCode;
        private String bodySystem;
        private Map<String, Object> triadDiagnosis;
        private Map<String, Object> triadInService;
        private Map<String, Object> triadNexus;
        private boolean isPresumptive;
        private String presumptiveBasis;
        private Integer estimatedRating;
        private String ratingRationale;
        private Double confidence;
        private List<Map<String, Object>> gaps;
        private List<Map<String, Object>> whatIfScenarios;
        private String pyramidGroup;
        private String pyramidReason;
        private Boolean pyramidPrimary;
        private Integer pyramidGroupRating;
        private String secondaryTo;
        private String ratingEvidenceNote;
        private Boolean excludedFromClaim;

        public Builder id(Long id) {
            this.id = id;
            return this;
        }

        public Builder claimId(Long claimId) {
            this.claimId = claimId;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder vasrdCode(String vasrdCode) {
            this.vasrdCode = vasrdCode;
            return this;
        }

        public Builder bodySystem(String bodySystem) {
            this.bodySystem = bodySystem;
            return this;
        }

        public Builder triadDiagnosis(Map<String, Object> triadDiagnosis) {
            this.triadDiagnosis = triadDiagnosis;
            return this;
        }

        public Builder triadInService(Map<String, Object> triadInService) {
            this.triadInService = triadInService;
            return this;
        }

        public Builder triadNexus(Map<String, Object> triadNexus) {
            this.triadNexus = triadNexus;
            return this;
        }

        public Builder isPresumptive(boolean isPresumptive) {
            this.isPresumptive = isPresumptive;
            return this;
        }

        public Builder presumptiveBasis(String presumptiveBasis) {
            this.presumptiveBasis = presumptiveBasis;
            return this;
        }

        public Builder estimatedRating(Integer estimatedRating) {
            this.estimatedRating = estimatedRating;
            return this;
        }

        public Builder ratingRationale(String ratingRationale) {
            this.ratingRationale = ratingRationale;
            return this;
        }

        public Builder confidence(Double confidence) {
            this.confidence = confidence;
            return this;
        }

        public Builder gaps(List<Map<String, Object>> gaps) {
            this.gaps = gaps;
            return this;
        }

        public Builder whatIfScenarios(List<Map<String, Object>> whatIfScenarios) {
            this.whatIfScenarios = whatIfScenarios;
            return this;
        }

        public Builder pyramidGroup(String pyramidGroup) {
            this.pyramidGroup = pyramidGroup;
            return this;
        }

        public Builder pyramidReason(String pyramidReason) {
            this.pyramidReason = pyramidReason;
            return this;
        }

        public Builder pyramidPrimary(Boolean pyramidPrimary) {
            this.pyramidPrimary = pyramidPrimary;
            return this;
        }

        public Builder pyramidGroupRating(Integer pyramidGroupRating) {
            this.pyramidGroupRating = pyramidGroupRating;
            return this;
        }

        public Builder secondaryTo(String secondaryTo) {
            this.secondaryTo = secondaryTo;
            return this;
        }

        public Builder ratingEvidenceNote(String ratingEvidenceNote) {
            this.ratingEvidenceNote = ratingEvidenceNote;
            return this;
        }

        public Builder excludedFromClaim(Boolean excludedFromClaim) {
            this.excludedFromClaim = excludedFromClaim;
            return this;
        }

        public ConditionResponse build() {
            ConditionResponse r = new ConditionResponse(id, claimId, name, vasrdCode, bodySystem,
                    triadDiagnosis, triadInService, triadNexus, isPresumptive, presumptiveBasis,
                    estimatedRating, ratingRationale, confidence, gaps, whatIfScenarios);
            r.pyramidGroup = pyramidGroup;
            r.pyramidReason = pyramidReason;
            r.pyramidPrimary = pyramidPrimary;
            r.pyramidGroupRating = pyramidGroupRating;
            r.secondaryTo = secondaryTo;
            r.ratingEvidenceNote = ratingEvidenceNote;
            r.excludedFromClaim = excludedFromClaim;
            return r;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ConditionResponse that = (ConditionResponse) o;
        return isPresumptive == that.isPresumptive &&
                Objects.equals(estimatedRating, that.estimatedRating) &&
                Objects.equals(confidence, that.confidence) &&
                Objects.equals(id, that.id) &&
                Objects.equals(claimId, that.claimId) &&
                Objects.equals(name, that.name) &&
                Objects.equals(vasrdCode, that.vasrdCode) &&
                Objects.equals(bodySystem, that.bodySystem) &&
                Objects.equals(triadDiagnosis, that.triadDiagnosis) &&
                Objects.equals(triadInService, that.triadInService) &&
                Objects.equals(triadNexus, that.triadNexus) &&
                Objects.equals(presumptiveBasis, that.presumptiveBasis) &&
                Objects.equals(ratingRationale, that.ratingRationale) &&
                Objects.equals(gaps, that.gaps) &&
                Objects.equals(whatIfScenarios, that.whatIfScenarios);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, claimId, name, vasrdCode, bodySystem, triadDiagnosis,
                triadInService, triadNexus, isPresumptive, presumptiveBasis, estimatedRating,
                ratingRationale, confidence, gaps, whatIfScenarios);
    }

    @Override
    public String toString() {
        return "ConditionResponse(" +
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
}
