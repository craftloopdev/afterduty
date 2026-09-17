package com.afterduty.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "pipeline_metrics")
public class PipelineMetrics {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "claim_id", nullable = false)
    private Long claimId;

    @Column(name = "run_timestamp", nullable = false)
    private Instant runTimestamp = Instant.now();

    // --- Extraction metrics ---
    @Column(name = "extraction_atom_count")
    private Integer extractionAtomCount;

    @Column(name = "extraction_medication_atom_count")
    private Integer extractionMedicationAtomCount;

    @Column(name = "extraction_service_record_atom_count")
    private Integer extractionServiceRecordAtomCount;

    @Column(name = "extraction_avg_confidence")
    private Double extractionAvgConfidence;

    @Column(name = "extraction_duration_ms")
    private Long extractionDurationMs;

    // --- Synthesis metrics ---
    @Column(name = "synthesis_condition_count")
    private Integer synthesisConditionCount;

    @Column(name = "synthesis_avg_rating")
    private Double synthesisAvgRating;

    @Column(name = "synthesis_avg_confidence")
    private Double synthesisAvgConfidence;

    @Column(name = "synthesis_valid_vasrd_code_count")
    private Integer synthesisValidVasrdCodeCount;

    @Column(name = "synthesis_duration_ms")
    private Long synthesisDurationMs;

    // --- Gap analysis metrics ---
    @Column(name = "gap_analysis_gap_count")
    private Integer gapAnalysisGapCount;

    @Column(name = "gap_analysis_specific_gap_count")
    private Integer gapAnalysisSpecificGapCount;

    @Column(name = "gap_analysis_what_if_count")
    private Integer gapAnalysisWhatIfCount;

    @Column(name = "gap_analysis_duration_ms")
    private Long gapAnalysisDurationMs;

    // --- Totals ---
    @Column(name = "total_duration_ms")
    private Long totalDurationMs;

    @Column(name = "total_tokens_used")
    private Long totalTokensUsed;

    @Column(name = "total_cost_usd")
    private Double totalCostUsd;

    @Column(columnDefinition = "text")
    private String notes;

    public PipelineMetrics() {
    }

    // --- Getters and setters ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getClaimId() { return claimId; }
    public void setClaimId(Long claimId) { this.claimId = claimId; }

    public Instant getRunTimestamp() { return runTimestamp; }
    public void setRunTimestamp(Instant runTimestamp) { this.runTimestamp = runTimestamp; }

    public Integer getExtractionAtomCount() { return extractionAtomCount; }
    public void setExtractionAtomCount(Integer extractionAtomCount) { this.extractionAtomCount = extractionAtomCount; }

    public Integer getExtractionMedicationAtomCount() { return extractionMedicationAtomCount; }
    public void setExtractionMedicationAtomCount(Integer extractionMedicationAtomCount) { this.extractionMedicationAtomCount = extractionMedicationAtomCount; }

    public Integer getExtractionServiceRecordAtomCount() { return extractionServiceRecordAtomCount; }
    public void setExtractionServiceRecordAtomCount(Integer extractionServiceRecordAtomCount) { this.extractionServiceRecordAtomCount = extractionServiceRecordAtomCount; }

    public Double getExtractionAvgConfidence() { return extractionAvgConfidence; }
    public void setExtractionAvgConfidence(Double extractionAvgConfidence) { this.extractionAvgConfidence = extractionAvgConfidence; }

    public Long getExtractionDurationMs() { return extractionDurationMs; }
    public void setExtractionDurationMs(Long extractionDurationMs) { this.extractionDurationMs = extractionDurationMs; }

    public Integer getSynthesisConditionCount() { return synthesisConditionCount; }
    public void setSynthesisConditionCount(Integer synthesisConditionCount) { this.synthesisConditionCount = synthesisConditionCount; }

    public Double getSynthesisAvgRating() { return synthesisAvgRating; }
    public void setSynthesisAvgRating(Double synthesisAvgRating) { this.synthesisAvgRating = synthesisAvgRating; }

    public Double getSynthesisAvgConfidence() { return synthesisAvgConfidence; }
    public void setSynthesisAvgConfidence(Double synthesisAvgConfidence) { this.synthesisAvgConfidence = synthesisAvgConfidence; }

    public Integer getSynthesisValidVasrdCodeCount() { return synthesisValidVasrdCodeCount; }
    public void setSynthesisValidVasrdCodeCount(Integer synthesisValidVasrdCodeCount) { this.synthesisValidVasrdCodeCount = synthesisValidVasrdCodeCount; }

    public Long getSynthesisDurationMs() { return synthesisDurationMs; }
    public void setSynthesisDurationMs(Long synthesisDurationMs) { this.synthesisDurationMs = synthesisDurationMs; }

    public Integer getGapAnalysisGapCount() { return gapAnalysisGapCount; }
    public void setGapAnalysisGapCount(Integer gapAnalysisGapCount) { this.gapAnalysisGapCount = gapAnalysisGapCount; }

    public Integer getGapAnalysisSpecificGapCount() { return gapAnalysisSpecificGapCount; }
    public void setGapAnalysisSpecificGapCount(Integer gapAnalysisSpecificGapCount) { this.gapAnalysisSpecificGapCount = gapAnalysisSpecificGapCount; }

    public Integer getGapAnalysisWhatIfCount() { return gapAnalysisWhatIfCount; }
    public void setGapAnalysisWhatIfCount(Integer gapAnalysisWhatIfCount) { this.gapAnalysisWhatIfCount = gapAnalysisWhatIfCount; }

    public Long getGapAnalysisDurationMs() { return gapAnalysisDurationMs; }
    public void setGapAnalysisDurationMs(Long gapAnalysisDurationMs) { this.gapAnalysisDurationMs = gapAnalysisDurationMs; }

    public Long getTotalDurationMs() { return totalDurationMs; }
    public void setTotalDurationMs(Long totalDurationMs) { this.totalDurationMs = totalDurationMs; }

    public Long getTotalTokensUsed() { return totalTokensUsed; }
    public void setTotalTokensUsed(Long totalTokensUsed) { this.totalTokensUsed = totalTokensUsed; }

    public Double getTotalCostUsd() { return totalCostUsd; }
    public void setTotalCostUsd(Double totalCostUsd) { this.totalCostUsd = totalCostUsd; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    @Override
    public String toString() {
        return "PipelineMetrics{" +
                "id=" + id +
                ", claimId=" + claimId +
                ", atoms=" + extractionAtomCount +
                ", conditions=" + synthesisConditionCount +
                ", gaps=" + gapAnalysisGapCount +
                ", totalDurationMs=" + totalDurationMs +
                '}';
    }
}
