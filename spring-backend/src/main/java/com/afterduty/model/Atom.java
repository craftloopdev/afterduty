package com.afterduty.model;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "atoms")
public class Atom {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "claim_id", nullable = false)
    private Long claimId;

    @Column(name = "evidence_id")
    private Long evidenceId;

    @Column(name = "message_id")
    private Long messageId;

    @Column(name = "creator_user_id")
    private Long creatorUserId;

    @Column(name = "atom_type", nullable = false)
    private String type;

    @Column(name = "atom_value", nullable = false, columnDefinition = "text")
    private String value;

    @Column(name = "atom_source", nullable = false, columnDefinition = "text")
    private String source;

    @Column(name = "created_by", nullable = false)
    private String createdBy = "ai:gemini";

    private Double confidence = 0.5;

    @Column(name = "atom_timestamp")
    private String timestamp;

    @Column(name = "metadata_json", columnDefinition = "text")
    private String metadataJson;

    @Column(name = "superseded_by")
    private Long supersededBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "claim_id", insertable = false, updatable = false)
    private Claim claim;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "evidence_id", insertable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_atom_evidence",
                    foreignKeyDefinition = "FOREIGN KEY (evidence_id) REFERENCES evidence_items(id) ON DELETE CASCADE"))
    private EvidenceItem evidence;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "message_id", insertable = false, updatable = false)
    private IntakeMessage message;

    public Atom() {
    }

    public Atom(Long id, Long claimId, Long evidenceId, Long messageId, Long creatorUserId,
                String type, String value, String source,
                String createdBy, Double confidence, String timestamp, String metadataJson, Long supersededBy,
                Instant createdAt, Claim claim, EvidenceItem evidence, IntakeMessage message) {
        this.id = id;
        this.claimId = claimId;
        this.evidenceId = evidenceId;
        this.messageId = messageId;
        this.creatorUserId = creatorUserId;
        this.type = type;
        this.value = value;
        this.source = source;
        this.createdBy = createdBy;
        this.confidence = confidence;
        this.timestamp = timestamp;
        this.metadataJson = metadataJson;
        this.supersededBy = supersededBy;
        this.createdAt = createdAt;
        this.claim = claim;
        this.evidence = evidence;
        this.message = message;
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

    public Long getEvidenceId() {
        return evidenceId;
    }

    public void setEvidenceId(Long evidenceId) {
        this.evidenceId = evidenceId;
    }

    public Long getMessageId() {
        return messageId;
    }

    public void setMessageId(Long messageId) {
        this.messageId = messageId;
    }

    public Long getCreatorUserId() {
        return creatorUserId;
    }

    public void setCreatorUserId(Long creatorUserId) {
        this.creatorUserId = creatorUserId;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public Double getConfidence() {
        return confidence;
    }

    public void setConfidence(Double confidence) {
        this.confidence = confidence;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(String timestamp) {
        this.timestamp = timestamp;
    }

    public String getMetadataJson() {
        return metadataJson;
    }

    public void setMetadataJson(String metadataJson) {
        this.metadataJson = metadataJson;
    }

    public Long getSupersededBy() {
        return supersededBy;
    }

    public void setSupersededBy(Long supersededBy) {
        this.supersededBy = supersededBy;
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

    public EvidenceItem getEvidence() {
        return evidence;
    }

    public void setEvidence(EvidenceItem evidence) {
        this.evidence = evidence;
    }

    public IntakeMessage getMessage() {
        return message;
    }

    public void setMessage(IntakeMessage message) {
        this.message = message;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Atom atom = (Atom) o;
        return Objects.equals(id, atom.id) &&
                Objects.equals(claimId, atom.claimId) &&
                Objects.equals(evidenceId, atom.evidenceId) &&
                Objects.equals(messageId, atom.messageId) &&
                Objects.equals(type, atom.type) &&
                Objects.equals(value, atom.value) &&
                Objects.equals(source, atom.source) &&
                Objects.equals(createdBy, atom.createdBy) &&
                Objects.equals(confidence, atom.confidence) &&
                Objects.equals(timestamp, atom.timestamp) &&
                Objects.equals(metadataJson, atom.metadataJson) &&
                Objects.equals(supersededBy, atom.supersededBy) &&
                Objects.equals(createdAt, atom.createdAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, claimId, evidenceId, messageId, type, value, source, createdBy, confidence, timestamp, metadataJson, supersededBy, createdAt);
    }

    @Override
    public String toString() {
        return "Atom(" +
                "id=" + id +
                ", claimId=" + claimId +
                ", evidenceId=" + evidenceId +
                ", messageId=" + messageId +
                ", type=" + type +
                ", value=" + value +
                ", source=" + source +
                ", createdBy=" + createdBy +
                ", confidence=" + confidence +
                ", timestamp=" + timestamp +
                ", metadataJson=" + metadataJson +
                ", supersededBy=" + supersededBy +
                ", createdAt=" + createdAt +
                ')';
    }

    public static AtomBuilder builder() {
        return new AtomBuilder();
    }

    public static class AtomBuilder {
        private Long id;
        private Long claimId;
        private Long evidenceId;
        private Long messageId;
        private Long creatorUserId;
        private String type;
        private String value;
        private String source;
        private String createdBy = "ai:gemini";
        private Double confidence = 0.5;
        private String timestamp;
        private String metadataJson;
        private Long supersededBy;
        private Instant createdAt = Instant.now();
        private Claim claim;
        private EvidenceItem evidence;
        private IntakeMessage message;

        AtomBuilder() {
        }

        public AtomBuilder id(Long id) {
            this.id = id;
            return this;
        }

        public AtomBuilder claimId(Long claimId) {
            this.claimId = claimId;
            return this;
        }

        public AtomBuilder evidenceId(Long evidenceId) {
            this.evidenceId = evidenceId;
            return this;
        }

        public AtomBuilder messageId(Long messageId) {
            this.messageId = messageId;
            return this;
        }

        public AtomBuilder creatorUserId(Long creatorUserId) {
            this.creatorUserId = creatorUserId;
            return this;
        }

        public AtomBuilder type(String type) {
            this.type = type;
            return this;
        }

        public AtomBuilder value(String value) {
            this.value = value;
            return this;
        }

        public AtomBuilder source(String source) {
            this.source = source;
            return this;
        }

        public AtomBuilder createdBy(String createdBy) {
            this.createdBy = createdBy;
            return this;
        }

        public AtomBuilder confidence(Double confidence) {
            this.confidence = confidence;
            return this;
        }

        public AtomBuilder timestamp(String timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public AtomBuilder metadataJson(String metadataJson) {
            this.metadataJson = metadataJson;
            return this;
        }

        public AtomBuilder supersededBy(Long supersededBy) {
            this.supersededBy = supersededBy;
            return this;
        }

        public AtomBuilder createdAt(Instant createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public AtomBuilder claim(Claim claim) {
            this.claim = claim;
            return this;
        }

        public AtomBuilder evidence(EvidenceItem evidence) {
            this.evidence = evidence;
            return this;
        }

        public AtomBuilder message(IntakeMessage message) {
            this.message = message;
            return this;
        }

        public Atom build() {
            return new Atom(id, claimId, evidenceId, messageId, creatorUserId, type, value, source, createdBy, confidence, timestamp, metadataJson, supersededBy, createdAt, claim, evidence, message);
        }
    }
}
