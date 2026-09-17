package com.afterduty.model;

import jakarta.persistence.*;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One retrievable text chunk — the unit of hybrid retrieval (Increment 7, spec §A.1).
 *
 * <p>A single table backs both corpora, discriminated by {@link #scope}:
 * <ul>
 *   <li>{@code scope = "evidence"} — a passage from the veteran's own uploaded
 *       document. Per-claim ({@code claimId} required, {@code evidenceId} set).</li>
 *   <li>{@code scope = "kb"} — a passage of VA regulation (38 CFR Parts 3 + 4).
 *       Global ({@code claimId} is NULL), carrying {@code kbSource}, {@code cfrSection},
 *       and the eCFR point-in-time {@link #asOfDate} that makes citations honest.</li>
 * </ul>
 *
 * <p><b>Deliberately NOT mapped here:</b> the {@code embedding vector(768)} and
 * {@code tsv tsvector} columns. Hibernate has no type for them and a
 * {@code columnDefinition} would make boot fail on any Postgres without the
 * {@code vector} extension (or on a non-PG test datasource). Those two columns are
 * created — guarded, idempotent, degrading gracefully — by
 * {@link com.afterduty.config.PgVectorBootstrap} after {@code ddl-auto} runs, and
 * every read/write of them goes through native SQL in the embedding pipeline (§B) and
 * {@code HybridRetrievalService} (§D). Because the entity never maps them,
 * {@code ddl-auto: update} neither creates nor fights them.
 */
@Entity
@Table(name = "chunks", indexes = {
        @Index(name = "idx_chunks_scope_claim", columnList = "scope, claim_id"),
        @Index(name = "idx_chunks_scope_kbsource", columnList = "scope, kb_source"),
        @Index(name = "idx_chunks_evidence", columnList = "evidence_id"),
        @Index(name = "idx_chunks_scope_cfr", columnList = "scope, cfr_section")
})
public class Chunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** {@code evidence} | {@code kb}. */
    @Column(name = "scope", nullable = false)
    private String scope;

    /** Required when scope=evidence; NULL for scope=kb. */
    @Column(name = "claim_id")
    private Long claimId;

    /**
     * FK → evidence_items(id) ON DELETE CASCADE so deleting a document reaps its
     * chunks. Mirrors the {@code Atom.evidence} foreign-key definition pattern.
     */
    @Column(name = "evidence_id")
    private Long evidenceId;

    /** {@code vasrd} | {@code presumptives} (later: {@code m21-1}, {@code dbq}). KB only. */
    @Column(name = "kb_source")
    private String kbSource;

    /** e.g. {@code 4.71a}, {@code 3.309} — KB chunks only. */
    @Column(name = "cfr_section")
    private String cfrSection;

    /** evidence: aiClassification echo; kb: {@code regulation}. */
    @Column(name = "doc_type")
    private String docType;

    /**
     * evidence: best-effort from aiExtractedData ({@code document_date} key) else
     * null — same loose-string convention as {@code Atom.timestamp}.
     */
    @Column(name = "doc_date")
    private String docDate;

    /** evidence: filename; kb: {@code ecfr}. */
    @Column(name = "source", nullable = false)
    private String source;

    /** KB chunks: the eCFR point-in-time date — THE citation-freshness field. */
    @Column(name = "as_of_date")
    private LocalDate asOfDate;

    /** Contextual header (doc title / CFR section heading), also prepended to {@link #content} before embedding. */
    @Column(name = "section_path")
    private String sectionPath;

    /** Chunk text, including the contextual header. */
    @Column(name = "content", nullable = false, columnDefinition = "text")
    private String content;

    /** Approx (chars/4), for budget accounting. */
    @Column(name = "token_count")
    private Integer tokenCount;

    /**
     * {@code pending} | {@code embedded} | {@code skipped} (skipped = pgvector
     * unavailable or rag disabled) — drives the backfill job (§B.4).
     */
    @Column(name = "embedding_status", nullable = false)
    private String embeddingStatus = "pending";

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public Chunk() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }

    public Long getClaimId() { return claimId; }
    public void setClaimId(Long claimId) { this.claimId = claimId; }

    public Long getEvidenceId() { return evidenceId; }
    public void setEvidenceId(Long evidenceId) { this.evidenceId = evidenceId; }

    public String getKbSource() { return kbSource; }
    public void setKbSource(String kbSource) { this.kbSource = kbSource; }

    public String getCfrSection() { return cfrSection; }
    public void setCfrSection(String cfrSection) { this.cfrSection = cfrSection; }

    public String getDocType() { return docType; }
    public void setDocType(String docType) { this.docType = docType; }

    public String getDocDate() { return docDate; }
    public void setDocDate(String docDate) { this.docDate = docDate; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public LocalDate getAsOfDate() { return asOfDate; }
    public void setAsOfDate(LocalDate asOfDate) { this.asOfDate = asOfDate; }

    public String getSectionPath() { return sectionPath; }
    public void setSectionPath(String sectionPath) { this.sectionPath = sectionPath; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public Integer getTokenCount() { return tokenCount; }
    public void setTokenCount(Integer tokenCount) { this.tokenCount = tokenCount; }

    public String getEmbeddingStatus() { return embeddingStatus; }
    public void setEmbeddingStatus(String embeddingStatus) { this.embeddingStatus = embeddingStatus; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private final Chunk c = new Chunk();
        public Builder scope(String v) { c.scope = v; return this; }
        public Builder claimId(Long v) { c.claimId = v; return this; }
        public Builder evidenceId(Long v) { c.evidenceId = v; return this; }
        public Builder kbSource(String v) { c.kbSource = v; return this; }
        public Builder cfrSection(String v) { c.cfrSection = v; return this; }
        public Builder docType(String v) { c.docType = v; return this; }
        public Builder docDate(String v) { c.docDate = v; return this; }
        public Builder source(String v) { c.source = v; return this; }
        public Builder asOfDate(LocalDate v) { c.asOfDate = v; return this; }
        public Builder sectionPath(String v) { c.sectionPath = v; return this; }
        public Builder content(String v) { c.content = v; return this; }
        public Builder tokenCount(Integer v) { c.tokenCount = v; return this; }
        public Builder embeddingStatus(String v) { c.embeddingStatus = v; return this; }
        public Builder createdAt(Instant v) { c.createdAt = v; return this; }
        public Chunk build() { return c; }
    }
}
