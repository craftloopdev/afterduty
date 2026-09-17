package com.afterduty.model;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Entity
@Table(name = "claims")
public class Claim {

    public enum ClaimType {
        INITIAL, INCREASE, APPEAL, SUPPLEMENTAL
    }

    public enum ClaimStatus {
        DRAFT, EXTRACTING, READY, SYNTHESIZING, ANALYZED, ERROR
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "claim_type", nullable = false)
    private ClaimType claimType = ClaimType.INITIAL;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ClaimStatus status = ClaimStatus.DRAFT;

    @Column(name = "synthesis_needed")
    private Boolean synthesisNeeded = false;

    @Column(name = "last_evidence_at")
    private Instant lastEvidenceAt;

    @Column(name = "last_analyzed_at")
    private Instant lastAnalyzedAt;

    @Column(name = "analysis_message", columnDefinition = "text")
    private String analysisMessage;

    @Column(name = "analysis_stage")
    private String analysisStage; // identifying, rating, verifying, gap_analysis, complete

    @Column(name = "analysis_progress_pct")
    private Integer analysisProgressPct = 0;

    // --- Auto-analysis state machine ---
    @Column(name = "last_synthesis_at")
    private Instant lastSynthesisAt;

    @Column(name = "last_synthesis_model")
    private String lastSynthesisModel;

    @Column(name = "last_gap_analysis_at")
    private Instant lastGapAnalysisAt;

    @Column(name = "last_gap_analysis_model")
    private String lastGapAnalysisModel;

    @Column(name = "synthesis_in_progress")
    private Boolean synthesisInProgress = false;

    @Column(name = "gap_analysis_in_progress")
    private Boolean gapAnalysisInProgress = false;

    // --- Async state-machine columns ---
    /**
     * Extraction state (per claim, per pipeline run). Values match
     * {@code ExtractionStateMachine.State}.
     */
    @Column(name = "extraction_state", length = 32)
    private String extractionState;

    /** Synthesis state, values match {@code SynthesisStateMachine.State}. */
    @Column(name = "synthesis_state", length = 32)
    private String synthesisState;

    /** Gap-analysis state, values match {@code GapStateMachine.State}. */
    @Column(name = "gap_state", length = 32)
    private String gapState;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at")
    private Instant updatedAt = Instant.now();

    @OneToMany(mappedBy = "claim", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<EvidenceItem> evidenceItems = new ArrayList<>();

    @OneToMany(mappedBy = "claim", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<IdentifiedCondition> identifiedConditions = new ArrayList<>();

    @OneToMany(mappedBy = "claim", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<IntakeMessage> messages = new ArrayList<>();

    @OneToMany(mappedBy = "claim", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<Atom> atoms = new ArrayList<>();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", insertable = false, updatable = false)
    private User user;

    public Claim() {
    }

    public Claim(Long id, Long userId, ClaimType claimType, ClaimStatus status, Boolean synthesisNeeded,
                 Instant lastEvidenceAt, Instant lastAnalyzedAt, Instant createdAt, Instant updatedAt,
                 List<EvidenceItem> evidenceItems, List<IdentifiedCondition> identifiedConditions,
                 List<IntakeMessage> messages, List<Atom> atoms, User user) {
        this.id = id;
        this.userId = userId;
        this.claimType = claimType;
        this.status = status;
        this.synthesisNeeded = synthesisNeeded;
        this.lastEvidenceAt = lastEvidenceAt;
        this.lastAnalyzedAt = lastAnalyzedAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.evidenceItems = evidenceItems;
        this.identifiedConditions = identifiedConditions;
        this.messages = messages;
        this.atoms = atoms;
        this.user = user;
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public ClaimType getClaimType() {
        return claimType;
    }

    public void setClaimType(ClaimType claimType) {
        this.claimType = claimType;
    }

    public ClaimStatus getStatus() {
        return status;
    }

    public void setStatus(ClaimStatus status) {
        this.status = status;
    }

    public Boolean getSynthesisNeeded() {
        return synthesisNeeded;
    }

    public void setSynthesisNeeded(Boolean synthesisNeeded) {
        this.synthesisNeeded = synthesisNeeded;
    }

    public Instant getLastEvidenceAt() {
        return lastEvidenceAt;
    }

    public void setLastEvidenceAt(Instant lastEvidenceAt) {
        this.lastEvidenceAt = lastEvidenceAt;
    }

    public Instant getLastAnalyzedAt() {
        return lastAnalyzedAt;
    }

    public void setLastAnalyzedAt(Instant lastAnalyzedAt) {
        this.lastAnalyzedAt = lastAnalyzedAt;
    }

    public String getAnalysisMessage() { return analysisMessage; }
    public void setAnalysisMessage(String analysisMessage) { this.analysisMessage = analysisMessage; }

    public String getAnalysisStage() { return analysisStage; }
    public void setAnalysisStage(String analysisStage) { this.analysisStage = analysisStage; }

    public Integer getAnalysisProgressPct() { return analysisProgressPct; }
    public void setAnalysisProgressPct(Integer analysisProgressPct) { this.analysisProgressPct = analysisProgressPct; }

    public Instant getLastSynthesisAt() { return lastSynthesisAt; }
    public void setLastSynthesisAt(Instant lastSynthesisAt) { this.lastSynthesisAt = lastSynthesisAt; }

    public String getLastSynthesisModel() { return lastSynthesisModel; }
    public void setLastSynthesisModel(String lastSynthesisModel) { this.lastSynthesisModel = lastSynthesisModel; }

    public Instant getLastGapAnalysisAt() { return lastGapAnalysisAt; }
    public void setLastGapAnalysisAt(Instant lastGapAnalysisAt) { this.lastGapAnalysisAt = lastGapAnalysisAt; }

    public String getLastGapAnalysisModel() { return lastGapAnalysisModel; }
    public void setLastGapAnalysisModel(String lastGapAnalysisModel) { this.lastGapAnalysisModel = lastGapAnalysisModel; }

    public Boolean getSynthesisInProgress() { return synthesisInProgress; }
    public void setSynthesisInProgress(Boolean synthesisInProgress) { this.synthesisInProgress = synthesisInProgress; }

    public Boolean getGapAnalysisInProgress() { return gapAnalysisInProgress; }
    public void setGapAnalysisInProgress(Boolean gapAnalysisInProgress) { this.gapAnalysisInProgress = gapAnalysisInProgress; }

    public String getExtractionState() { return extractionState; }
    public void setExtractionState(String extractionState) { this.extractionState = extractionState; }

    public String getSynthesisState() { return synthesisState; }
    public void setSynthesisState(String synthesisState) { this.synthesisState = synthesisState; }

    public String getGapState() { return gapState; }
    public void setGapState(String gapState) { this.gapState = gapState; }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public List<EvidenceItem> getEvidenceItems() {
        return evidenceItems;
    }

    public void setEvidenceItems(List<EvidenceItem> evidenceItems) {
        this.evidenceItems = evidenceItems;
    }

    public List<IdentifiedCondition> getIdentifiedConditions() {
        return identifiedConditions;
    }

    public void setIdentifiedConditions(List<IdentifiedCondition> identifiedConditions) {
        this.identifiedConditions = identifiedConditions;
    }

    public List<IntakeMessage> getMessages() {
        return messages;
    }

    public void setMessages(List<IntakeMessage> messages) {
        this.messages = messages;
    }

    public List<Atom> getAtoms() {
        return atoms;
    }

    public void setAtoms(List<Atom> atoms) {
        this.atoms = atoms;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Claim that = (Claim) o;
        return Objects.equals(id, that.id) &&
                Objects.equals(userId, that.userId) &&
                claimType == that.claimType &&
                status == that.status &&
                Objects.equals(synthesisNeeded, that.synthesisNeeded) &&
                Objects.equals(lastEvidenceAt, that.lastEvidenceAt) &&
                Objects.equals(lastAnalyzedAt, that.lastAnalyzedAt) &&
                Objects.equals(createdAt, that.createdAt) &&
                Objects.equals(updatedAt, that.updatedAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, userId, claimType, status, synthesisNeeded, lastEvidenceAt, lastAnalyzedAt, createdAt, updatedAt);
    }

    @Override
    public String toString() {
        return "Claim(" +
                "id=" + id +
                ", userId=" + userId +
                ", claimType=" + claimType +
                ", status=" + status +
                ", synthesisNeeded=" + synthesisNeeded +
                ", lastEvidenceAt=" + lastEvidenceAt +
                ", lastAnalyzedAt=" + lastAnalyzedAt +
                ", createdAt=" + createdAt +
                ", updatedAt=" + updatedAt +
                ')';
    }

    public static ClaimBuilder builder() {
        return new ClaimBuilder();
    }

    public static class ClaimBuilder {
        private Long id;
        private Long userId;
        private ClaimType claimType = ClaimType.INITIAL;
        private ClaimStatus status = ClaimStatus.DRAFT;
        private Boolean synthesisNeeded = false;
        private Instant lastEvidenceAt;
        private Instant lastAnalyzedAt;
        private Instant createdAt = Instant.now();
        private Instant updatedAt = Instant.now();
        private List<EvidenceItem> evidenceItems = new ArrayList<>();
        private List<IdentifiedCondition> identifiedConditions = new ArrayList<>();
        private List<IntakeMessage> messages = new ArrayList<>();
        private List<Atom> atoms = new ArrayList<>();
        private User user;

        ClaimBuilder() {
        }

        public ClaimBuilder id(Long id) {
            this.id = id;
            return this;
        }

        public ClaimBuilder userId(Long userId) {
            this.userId = userId;
            return this;
        }

        public ClaimBuilder claimType(ClaimType claimType) {
            this.claimType = claimType;
            return this;
        }

        public ClaimBuilder status(ClaimStatus status) {
            this.status = status;
            return this;
        }

        public ClaimBuilder synthesisNeeded(Boolean synthesisNeeded) {
            this.synthesisNeeded = synthesisNeeded;
            return this;
        }

        public ClaimBuilder lastEvidenceAt(Instant lastEvidenceAt) {
            this.lastEvidenceAt = lastEvidenceAt;
            return this;
        }

        public ClaimBuilder lastAnalyzedAt(Instant lastAnalyzedAt) {
            this.lastAnalyzedAt = lastAnalyzedAt;
            return this;
        }

        public ClaimBuilder createdAt(Instant createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public ClaimBuilder updatedAt(Instant updatedAt) {
            this.updatedAt = updatedAt;
            return this;
        }

        public ClaimBuilder evidenceItems(List<EvidenceItem> evidenceItems) {
            this.evidenceItems = evidenceItems;
            return this;
        }

        public ClaimBuilder identifiedConditions(List<IdentifiedCondition> identifiedConditions) {
            this.identifiedConditions = identifiedConditions;
            return this;
        }

        public ClaimBuilder messages(List<IntakeMessage> messages) {
            this.messages = messages;
            return this;
        }

        public ClaimBuilder atoms(List<Atom> atoms) {
            this.atoms = atoms;
            return this;
        }

        public ClaimBuilder user(User user) {
            this.user = user;
            return this;
        }

        public Claim build() {
            return new Claim(id, userId, claimType, status, synthesisNeeded, lastEvidenceAt, lastAnalyzedAt, createdAt, updatedAt, evidenceItems, identifiedConditions, messages, atoms, user);
        }
    }
}
