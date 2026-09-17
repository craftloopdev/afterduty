package com.afterduty.model;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "medical_events")
public class MedicalEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "claim_id", nullable = false)
    private Long claimId;

    @Column(name = "evidence_id", nullable = false)
    private Long evidenceId;

    @Column(name = "event_date")
    private String eventDate;

    @Column(name = "event_type")
    private String eventType;

    @Column(name = "provider")
    private String provider;

    @Column(name = "facility")
    private String facility;

    @Column(name = "summary", columnDefinition = "text")
    private String summary;

    @Column(name = "raw_text", columnDefinition = "text")
    private String rawText;

    @Column(name = "page_range")
    private String pageRange;

    @Column(name = "extracted")
    private Boolean extracted = false;

    @Column(name = "atom_count")
    private Integer atomCount = 0;

    @Column(name = "medications_mentioned", columnDefinition = "text")
    private String medicationsMentioned;

    @Column(name = "diagnoses_mentioned", columnDefinition = "text")
    private String diagnosesMentioned;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "claim_id", insertable = false, updatable = false)
    private Claim claim;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "evidence_id", insertable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_medical_event_evidence",
                    foreignKeyDefinition = "FOREIGN KEY (evidence_id) REFERENCES evidence_items(id) ON DELETE CASCADE"))
    private EvidenceItem evidence;

    public MedicalEvent() {
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

    public String getEventDate() {
        return eventDate;
    }

    public void setEventDate(String eventDate) {
        this.eventDate = eventDate;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getFacility() {
        return facility;
    }

    public void setFacility(String facility) {
        this.facility = facility;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getRawText() {
        return rawText;
    }

    public void setRawText(String rawText) {
        this.rawText = rawText;
    }

    public String getPageRange() {
        return pageRange;
    }

    public void setPageRange(String pageRange) {
        this.pageRange = pageRange;
    }

    public Boolean getExtracted() {
        return extracted;
    }

    public void setExtracted(Boolean extracted) {
        this.extracted = extracted;
    }

    public Integer getAtomCount() {
        return atomCount;
    }

    public void setAtomCount(Integer atomCount) {
        this.atomCount = atomCount;
    }

    public String getMedicationsMentioned() {
        return medicationsMentioned;
    }

    public void setMedicationsMentioned(String medicationsMentioned) {
        this.medicationsMentioned = medicationsMentioned;
    }

    public String getDiagnosesMentioned() {
        return diagnosesMentioned;
    }

    public void setDiagnosesMentioned(String diagnosesMentioned) {
        this.diagnosesMentioned = diagnosesMentioned;
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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MedicalEvent that = (MedicalEvent) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "MedicalEvent(" +
                "id=" + id +
                ", claimId=" + claimId +
                ", evidenceId=" + evidenceId +
                ", eventDate=" + eventDate +
                ", eventType=" + eventType +
                ", provider=" + provider +
                ", facility=" + facility +
                ", summary=" + summary +
                ", extracted=" + extracted +
                ", atomCount=" + atomCount +
                ')';
    }

    public static MedicalEventBuilder builder() {
        return new MedicalEventBuilder();
    }

    public static class MedicalEventBuilder {
        private Long claimId;
        private Long evidenceId;
        private String eventDate;
        private String eventType;
        private String provider;
        private String facility;
        private String summary;
        private String rawText;
        private String pageRange;
        private String medicationsMentioned;
        private String diagnosesMentioned;

        MedicalEventBuilder() {
        }

        public MedicalEventBuilder claimId(Long claimId) {
            this.claimId = claimId;
            return this;
        }

        public MedicalEventBuilder evidenceId(Long evidenceId) {
            this.evidenceId = evidenceId;
            return this;
        }

        public MedicalEventBuilder eventDate(String eventDate) {
            this.eventDate = eventDate;
            return this;
        }

        public MedicalEventBuilder eventType(String eventType) {
            this.eventType = eventType;
            return this;
        }

        public MedicalEventBuilder provider(String provider) {
            this.provider = provider;
            return this;
        }

        public MedicalEventBuilder facility(String facility) {
            this.facility = facility;
            return this;
        }

        public MedicalEventBuilder summary(String summary) {
            this.summary = summary;
            return this;
        }

        public MedicalEventBuilder rawText(String rawText) {
            this.rawText = rawText;
            return this;
        }

        public MedicalEventBuilder pageRange(String pageRange) {
            this.pageRange = pageRange;
            return this;
        }

        public MedicalEventBuilder medicationsMentioned(String medicationsMentioned) {
            this.medicationsMentioned = medicationsMentioned;
            return this;
        }

        public MedicalEventBuilder diagnosesMentioned(String diagnosesMentioned) {
            this.diagnosesMentioned = diagnosesMentioned;
            return this;
        }

        public MedicalEvent build() {
            MedicalEvent event = new MedicalEvent();
            event.setClaimId(claimId);
            event.setEvidenceId(evidenceId);
            event.setEventDate(eventDate);
            event.setEventType(eventType);
            event.setProvider(provider);
            event.setFacility(facility);
            event.setSummary(summary);
            event.setRawText(rawText);
            event.setPageRange(pageRange);
            event.setMedicationsMentioned(medicationsMentioned);
            event.setDiagnosesMentioned(diagnosesMentioned);
            return event;
        }
    }
}
