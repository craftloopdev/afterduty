package com.afterduty.model;

import jakarta.persistence.*;

import java.time.LocalDate;

/**
 * One rating tier of one diagnostic code from 38 CFR Part 4 — the deterministic
 * side of the rating schedule (Increment 7, spec §A.2; research-va Implication 3:
 * rating tables must be structured records, not just retrievable chunks).
 *
 * <p>One row per {@code (dcCode, ratingPct)} tier. {@code ratingPct} is null for
 * note-only rows. Ingested from the eCFR Part-4 XML by
 * {@code VasrdIngestService}; consumed deterministically by {@code VasrdDataService}
 * (DB-first) and the {@code vasrd_lookup} chat tool. {@link #asOfDate} stamps each
 * row with the eCFR issue date so a lookup can cite "current as of".
 */
@Entity
@Table(name = "vasrd_records", indexes = {
        @Index(name = "idx_vasrd_dc_code", columnList = "dc_code"),
        @Index(name = "idx_vasrd_cfr_section", columnList = "cfr_section")
})
public class VasrdRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 4-digit diagnostic code, e.g. {@code 5260}. */
    @Column(name = "dc_code", nullable = false)
    private String dcCode;

    /** Condition name from the schedule. */
    @Column(name = "title", columnDefinition = "text")
    private String title;

    /** From the subpart/section, e.g. {@code musculoskeletal}. */
    @Column(name = "body_system")
    private String bodySystem;

    /** e.g. {@code 4.71a}. */
    @Column(name = "cfr_section", nullable = false)
    private String cfrSection;

    /** One row per (dc_code, rating tier); null for note-only rows. */
    @Column(name = "rating_pct")
    private Integer ratingPct;

    /** The criteria for that tier. */
    @Column(name = "criteria_text", columnDefinition = "text")
    private String criteriaText;

    /** eCFR issue date of the ingest. */
    @Column(name = "as_of_date", nullable = false)
    private LocalDate asOfDate;

    /** Preserves schedule order within a DC. */
    @Column(name = "display_order")
    private Integer displayOrder;

    public VasrdRecord() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getDcCode() { return dcCode; }
    public void setDcCode(String dcCode) { this.dcCode = dcCode; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getBodySystem() { return bodySystem; }
    public void setBodySystem(String bodySystem) { this.bodySystem = bodySystem; }

    public String getCfrSection() { return cfrSection; }
    public void setCfrSection(String cfrSection) { this.cfrSection = cfrSection; }

    public Integer getRatingPct() { return ratingPct; }
    public void setRatingPct(Integer ratingPct) { this.ratingPct = ratingPct; }

    public String getCriteriaText() { return criteriaText; }
    public void setCriteriaText(String criteriaText) { this.criteriaText = criteriaText; }

    public LocalDate getAsOfDate() { return asOfDate; }
    public void setAsOfDate(LocalDate asOfDate) { this.asOfDate = asOfDate; }

    public Integer getDisplayOrder() { return displayOrder; }
    public void setDisplayOrder(Integer displayOrder) { this.displayOrder = displayOrder; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private final VasrdRecord r = new VasrdRecord();
        public Builder dcCode(String v) { r.dcCode = v; return this; }
        public Builder title(String v) { r.title = v; return this; }
        public Builder bodySystem(String v) { r.bodySystem = v; return this; }
        public Builder cfrSection(String v) { r.cfrSection = v; return this; }
        public Builder ratingPct(Integer v) { r.ratingPct = v; return this; }
        public Builder criteriaText(String v) { r.criteriaText = v; return this; }
        public Builder asOfDate(LocalDate v) { r.asOfDate = v; return this; }
        public Builder displayOrder(Integer v) { r.displayOrder = v; return this; }
        public VasrdRecord build() { return r; }
    }
}
