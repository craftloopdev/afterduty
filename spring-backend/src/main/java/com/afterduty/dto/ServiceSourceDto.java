package com.afterduty.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Objects;

/**
 * One contributing raw record behind a reconciled {@link ServicePeriodDto}
 * conclusion — the receipts for the future Service History drill-down modal
 * (design 2026-07-05, "show the conclusion, keep the receipts").
 *
 * <p>Each raw per-document period (or the manual/self-statement row) that merged
 * into an enlistment surfaces here with its owning evidence, the document type it
 * was classified as, the authority rank that type carries, and the RAW field
 * values as they appeared on that record (before the conclusion picked winners).
 *
 * <p>Additive + emitted only inside the (nullable) {@code sources} list of a
 * reconciled conclusion, so it never affects the pinned legacy profile-card
 * contract. Null fields are omitted (NON_NULL) — a self-statement has no
 * {@code evidenceId}, an undated record has null raw dates, etc.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ServiceSourceDto {

    /** Owning EvidenceItem id, or null for the manual/self-statement row. */
    private Long evidenceId;

    /** Document type this source was classified as (EvidenceItem.aiClassification,
     *  or {@code "manual"} for the veteran-entered row). Null when unknown. */
    private String docType;

    /** VA-style authority rank of {@link #docType} (higher wins). See
     *  {@code ServiceHistoryReconciler} for the mapping. */
    private Integer authorityRank;

    // Raw field values exactly as this record carried them, pre-reconciliation.
    private String rawBranch;
    private String rawStart;
    private String rawEnd;
    private String rawMos;
    private String rawRank;

    public ServiceSourceDto() {
    }

    public ServiceSourceDto(Long evidenceId, String docType, Integer authorityRank,
                            String rawBranch, String rawStart, String rawEnd,
                            String rawMos, String rawRank) {
        this.evidenceId = evidenceId;
        this.docType = docType;
        this.authorityRank = authorityRank;
        this.rawBranch = rawBranch;
        this.rawStart = rawStart;
        this.rawEnd = rawEnd;
        this.rawMos = rawMos;
        this.rawRank = rawRank;
    }

    public Long getEvidenceId() {
        return evidenceId;
    }

    public void setEvidenceId(Long evidenceId) {
        this.evidenceId = evidenceId;
    }

    public String getDocType() {
        return docType;
    }

    public void setDocType(String docType) {
        this.docType = docType;
    }

    public Integer getAuthorityRank() {
        return authorityRank;
    }

    public void setAuthorityRank(Integer authorityRank) {
        this.authorityRank = authorityRank;
    }

    public String getRawBranch() {
        return rawBranch;
    }

    public void setRawBranch(String rawBranch) {
        this.rawBranch = rawBranch;
    }

    public String getRawStart() {
        return rawStart;
    }

    public void setRawStart(String rawStart) {
        this.rawStart = rawStart;
    }

    public String getRawEnd() {
        return rawEnd;
    }

    public void setRawEnd(String rawEnd) {
        this.rawEnd = rawEnd;
    }

    public String getRawMos() {
        return rawMos;
    }

    public void setRawMos(String rawMos) {
        this.rawMos = rawMos;
    }

    public String getRawRank() {
        return rawRank;
    }

    public void setRawRank(String rawRank) {
        this.rawRank = rawRank;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ServiceSourceDto that = (ServiceSourceDto) o;
        return Objects.equals(evidenceId, that.evidenceId) &&
                Objects.equals(docType, that.docType) &&
                Objects.equals(authorityRank, that.authorityRank) &&
                Objects.equals(rawBranch, that.rawBranch) &&
                Objects.equals(rawStart, that.rawStart) &&
                Objects.equals(rawEnd, that.rawEnd) &&
                Objects.equals(rawMos, that.rawMos) &&
                Objects.equals(rawRank, that.rawRank);
    }

    @Override
    public int hashCode() {
        return Objects.hash(evidenceId, docType, authorityRank, rawBranch, rawStart, rawEnd, rawMos, rawRank);
    }

    @Override
    public String toString() {
        return "ServiceSourceDto(" +
                "evidenceId=" + evidenceId +
                ", docType=" + docType +
                ", authorityRank=" + authorityRank +
                ", rawBranch=" + rawBranch +
                ", rawStart=" + rawStart +
                ", rawEnd=" + rawEnd +
                ", rawMos=" + rawMos +
                ", rawRank=" + rawRank +
                ')';
    }
}
