package com.afterduty.model;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Entity
@Table(name = "evidence_items")
public class EvidenceItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "claim_id", nullable = false)
    private Long claimId;

    @Column(name = "source_type", nullable = false)
    private String sourceType;

    @Column(name = "raw_content", columnDefinition = "text")
    private String rawContent;

    private String filename;

    @Column(name = "anthropic_file_id")
    private String anthropicFileId;

    @Column(name = "media_type")
    private String mediaType;

    @Column(name = "gcs_path")
    private String gcsPath;

    @Column(name = "file_hash")
    private String fileHash;

    @Column(name = "file_size")
    private Long fileSize;

    /**
     * Content-addressed extraction key — Mission 5a incremental extraction.
     *
     * <p>A deterministic hash of {@code (content discriminator, prompt_version,
     * schema_version, extraction model id)} written ONLY after this document's
     * single-pass parse+persist has succeeded. On the next extraction run the
     * fan-out skips any document whose stored {@code extract_key} already equals
     * the freshly computed key — meaning the bytes, the extraction prompt, the
     * output schema, and the routed model are all unchanged, so re-extracting
     * would produce identical atoms. A null key (never extracted, or a prior
     * failure that left it null) and a mismatched key (file changed, or a
     * prompt/schema/model bump) both re-extract. See
     * {@code SinglePassExtractionService.computeExtractKey}.
     *
     * <p>Behind {@code va-claim.analysis.incremental}; flag-off never reads or
     * writes this column, preserving the full re-extract-everything semantics.
     */
    @Column(name = "extract_key")
    private String extractKey;

    @Column(name = "ai_classification")
    private String aiClassification;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "ai_extracted_data", columnDefinition = "text")
    private Map<String, Object> aiExtractedData;

    @Column(name = "ai_summary", columnDefinition = "text")
    private String aiSummary;

    @Column(name = "processing_status")
    private String processingStatus = "pending";

    @Column(name = "processing_message", columnDefinition = "text")
    private String processingMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "claim_id", insertable = false, updatable = false)
    private Claim claim;

    @OneToMany(mappedBy = "evidence", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<Atom> atoms = new ArrayList<>();

    public EvidenceItem() {
    }

    public EvidenceItem(Long id, Long claimId, String sourceType, String rawContent, String filename,
                        String anthropicFileId, String mediaType, String gcsPath, String fileHash,
                        Long fileSize, String extractKey, String aiClassification,
                        Map<String, Object> aiExtractedData,
                        String aiSummary, String processingStatus, Instant createdAt, Claim claim,
                        List<Atom> atoms) {
        this.id = id;
        this.claimId = claimId;
        this.sourceType = sourceType;
        this.rawContent = rawContent;
        this.filename = filename;
        this.anthropicFileId = anthropicFileId;
        this.mediaType = mediaType;
        this.gcsPath = gcsPath;
        this.fileHash = fileHash;
        this.fileSize = fileSize;
        this.extractKey = extractKey;
        this.aiClassification = aiClassification;
        this.aiExtractedData = aiExtractedData;
        this.aiSummary = aiSummary;
        this.processingStatus = processingStatus;
        this.createdAt = createdAt;
        this.claim = claim;
        this.atoms = atoms;
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

    public String getSourceType() {
        return sourceType;
    }

    public void setSourceType(String sourceType) {
        this.sourceType = sourceType;
    }

    public String getRawContent() {
        return rawContent;
    }

    public void setRawContent(String rawContent) {
        this.rawContent = rawContent;
    }

    public String getFilename() {
        return filename;
    }

    public void setFilename(String filename) {
        this.filename = filename;
    }

    public String getAnthropicFileId() {
        return anthropicFileId;
    }

    public void setAnthropicFileId(String anthropicFileId) {
        this.anthropicFileId = anthropicFileId;
    }

    public String getMediaType() {
        return mediaType;
    }

    public void setMediaType(String mediaType) {
        this.mediaType = mediaType;
    }

    public String getGcsPath() {
        return gcsPath;
    }

    public void setGcsPath(String gcsPath) {
        this.gcsPath = gcsPath;
    }

    public String getFileHash() {
        return fileHash;
    }

    public void setFileHash(String fileHash) {
        this.fileHash = fileHash;
    }

    public Long getFileSize() {
        return fileSize;
    }

    public void setFileSize(Long fileSize) {
        this.fileSize = fileSize;
    }

    public String getExtractKey() {
        return extractKey;
    }

    public void setExtractKey(String extractKey) {
        this.extractKey = extractKey;
    }

    public String getAiClassification() {
        return aiClassification;
    }

    public void setAiClassification(String aiClassification) {
        this.aiClassification = aiClassification;
    }

    public Map<String, Object> getAiExtractedData() {
        return aiExtractedData;
    }

    public void setAiExtractedData(Map<String, Object> aiExtractedData) {
        this.aiExtractedData = aiExtractedData;
    }

    public String getAiSummary() {
        return aiSummary;
    }

    public void setAiSummary(String aiSummary) {
        this.aiSummary = aiSummary;
    }

    public String getProcessingStatus() {
        return processingStatus;
    }

    public void setProcessingStatus(String processingStatus) {
        this.processingStatus = processingStatus;
    }

    public String getProcessingMessage() {
        return processingMessage;
    }

    public void setProcessingMessage(String processingMessage) {
        this.processingMessage = processingMessage;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Claim getClaim() {
        return claim;
    }

    public void setClaim(Claim claim) {
        this.claim = claim;
    }

    public List<Atom> getAtoms() {
        return atoms;
    }

    public void setAtoms(List<Atom> atoms) {
        this.atoms = atoms;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        EvidenceItem that = (EvidenceItem) o;
        return Objects.equals(id, that.id) &&
                Objects.equals(claimId, that.claimId) &&
                Objects.equals(sourceType, that.sourceType) &&
                Objects.equals(rawContent, that.rawContent) &&
                Objects.equals(filename, that.filename) &&
                Objects.equals(anthropicFileId, that.anthropicFileId) &&
                Objects.equals(mediaType, that.mediaType) &&
                Objects.equals(gcsPath, that.gcsPath) &&
                Objects.equals(aiClassification, that.aiClassification) &&
                Objects.equals(aiExtractedData, that.aiExtractedData) &&
                Objects.equals(aiSummary, that.aiSummary) &&
                Objects.equals(processingStatus, that.processingStatus) &&
                Objects.equals(createdAt, that.createdAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, claimId, sourceType, rawContent, filename, anthropicFileId, mediaType, gcsPath, aiClassification, aiExtractedData, aiSummary, processingStatus, createdAt);
    }

    @Override
    public String toString() {
        return "EvidenceItem(" +
                "id=" + id +
                ", claimId=" + claimId +
                ", sourceType=" + sourceType +
                ", rawContent=" + rawContent +
                ", filename=" + filename +
                ", anthropicFileId=" + anthropicFileId +
                ", mediaType=" + mediaType +
                ", gcsPath=" + gcsPath +
                ", aiClassification=" + aiClassification +
                ", aiExtractedData=" + aiExtractedData +
                ", aiSummary=" + aiSummary +
                ", processingStatus=" + processingStatus +
                ", createdAt=" + createdAt +
                ')';
    }

    public static EvidenceItemBuilder builder() {
        return new EvidenceItemBuilder();
    }

    public static class EvidenceItemBuilder {
        private Long id;
        private Long claimId;
        private String sourceType;
        private String rawContent;
        private String filename;
        private String anthropicFileId;
        private String mediaType;
        private String gcsPath;
        private String fileHash;
        private Long fileSize;
        private String extractKey;
        private String aiClassification;
        private Map<String, Object> aiExtractedData;
        private String aiSummary;
        private String processingStatus = "pending";
        private Instant createdAt = Instant.now();
        private Claim claim;
        private List<Atom> atoms = new ArrayList<>();

        EvidenceItemBuilder() {
        }

        public EvidenceItemBuilder id(Long id) {
            this.id = id;
            return this;
        }

        public EvidenceItemBuilder claimId(Long claimId) {
            this.claimId = claimId;
            return this;
        }

        public EvidenceItemBuilder sourceType(String sourceType) {
            this.sourceType = sourceType;
            return this;
        }

        public EvidenceItemBuilder rawContent(String rawContent) {
            this.rawContent = rawContent;
            return this;
        }

        public EvidenceItemBuilder filename(String filename) {
            this.filename = filename;
            return this;
        }

        public EvidenceItemBuilder anthropicFileId(String anthropicFileId) {
            this.anthropicFileId = anthropicFileId;
            return this;
        }

        public EvidenceItemBuilder mediaType(String mediaType) {
            this.mediaType = mediaType;
            return this;
        }

        public EvidenceItemBuilder gcsPath(String gcsPath) {
            this.gcsPath = gcsPath;
            return this;
        }

        public EvidenceItemBuilder fileHash(String fileHash) {
            this.fileHash = fileHash;
            return this;
        }

        public EvidenceItemBuilder fileSize(Long fileSize) {
            this.fileSize = fileSize;
            return this;
        }

        public EvidenceItemBuilder extractKey(String extractKey) {
            this.extractKey = extractKey;
            return this;
        }

        public EvidenceItemBuilder aiClassification(String aiClassification) {
            this.aiClassification = aiClassification;
            return this;
        }

        public EvidenceItemBuilder aiExtractedData(Map<String, Object> aiExtractedData) {
            this.aiExtractedData = aiExtractedData;
            return this;
        }

        public EvidenceItemBuilder aiSummary(String aiSummary) {
            this.aiSummary = aiSummary;
            return this;
        }

        public EvidenceItemBuilder processingStatus(String processingStatus) {
            this.processingStatus = processingStatus;
            return this;
        }

        public EvidenceItemBuilder createdAt(Instant createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public EvidenceItemBuilder claim(Claim claim) {
            this.claim = claim;
            return this;
        }

        public EvidenceItemBuilder atoms(List<Atom> atoms) {
            this.atoms = atoms;
            return this;
        }

        public EvidenceItem build() {
            return new EvidenceItem(id, claimId, sourceType, rawContent, filename, anthropicFileId, mediaType, gcsPath, fileHash, fileSize, extractKey, aiClassification, aiExtractedData, aiSummary, processingStatus, createdAt, claim, atoms);
        }
    }
}
