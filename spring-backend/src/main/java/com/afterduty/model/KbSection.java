package com.afterduty.model;

import jakarta.persistence.*;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Per-section ingest watermark for the nightly eCFR diff (Increment 7, spec §A.2).
 *
 * <p>One row per ingested 38 CFR section. {@link #lastIssueDate} is the latest eCFR
 * {@code /versions} issue_date already ingested for that section; the nightly
 * {@code KbRefreshJob} compares title-38's {@code up_to_date_as_of} against the max
 * watermark and re-ingests only sections whose version advanced. {@link #contentHash}
 * (SHA-256 of the section XML) is a belt-and-braces check against the versions feed.
 */
@Entity
@Table(name = "kb_sections", uniqueConstraints = {
        @UniqueConstraint(name = "uq_kb_section_part_id", columnNames = {"part", "section_identifier"})
})
public class KbSection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 3 or 4. */
    @Column(name = "part", nullable = false)
    private Integer part;

    /** e.g. {@code 4.71a}. Unique with {@link #part}. */
    @Column(name = "section_identifier", nullable = false)
    private String sectionIdentifier;

    /** Latest eCFR {@code /versions} issue_date ingested. */
    @Column(name = "last_issue_date")
    private LocalDate lastIssueDate;

    /** SHA-256 of the section XML. */
    @Column(name = "content_hash")
    private String contentHash;

    @Column(name = "last_ingested_at")
    private Instant lastIngestedAt;

    public KbSection() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Integer getPart() { return part; }
    public void setPart(Integer part) { this.part = part; }

    public String getSectionIdentifier() { return sectionIdentifier; }
    public void setSectionIdentifier(String sectionIdentifier) { this.sectionIdentifier = sectionIdentifier; }

    public LocalDate getLastIssueDate() { return lastIssueDate; }
    public void setLastIssueDate(LocalDate lastIssueDate) { this.lastIssueDate = lastIssueDate; }

    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }

    public Instant getLastIngestedAt() { return lastIngestedAt; }
    public void setLastIngestedAt(Instant lastIngestedAt) { this.lastIngestedAt = lastIngestedAt; }
}
