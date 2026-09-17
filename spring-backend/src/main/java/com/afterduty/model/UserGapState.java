package com.afterduty.model;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * P1-6 — durable, user-set gap status that survives re-analysis.
 *
 * <p>A gap run wholesale-replaces every condition's {@code gaps} JSON, which
 * used to destroy anything the veteran had marked resolved or dismissed. This
 * row is the durable record of that user intent, keyed NOT by condition id
 * (ids change every generation) but by the condition's stable
 * {@code identity_fingerprint} plus the gap's {@code (gap_type, triad_leg)}
 * pair — the same gap re-proposed for the same condition in a later
 * generation lands on the same key.
 *
 * <p>Lifecycle: written by {@code PATCH /api/claim/gaps/{condId}/{gapIndex}/status}
 * (via {@code UserGapStateService.upsert}); re-applied onto freshly-written gap
 * JSON after every gap run ({@code GapStateMachine} → {@code reapply}); rows
 * with status {@code dismissed} are additionally injected into the gap-analysis
 * prompt as do-not-re-propose context ({@code EvidenceGapAnalyzer}).
 *
 * <p>The unique key treats a NULL {@code triad_leg} as its own slot at the app
 * layer (the upsert has a dedicated IsNull lookup); at the DB layer NULLs are
 * distinct in a UNIQUE constraint, so the service-level upsert is the guard
 * against duplicate null-leg rows.
 */
@Entity
@Table(name = "user_gap_state",
        uniqueConstraints = @UniqueConstraint(name = "uq_user_gap_state_key",
                columnNames = {"claim_id", "identity_fingerprint", "gap_type", "triad_leg"}),
        indexes = @Index(name = "idx_user_gap_state_claim", columnList = "claim_id"))
public class UserGapState {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "claim_id", nullable = false)
    private Long claimId;

    /** The owning condition's stable identity fingerprint (survives generations). */
    @Column(name = "identity_fingerprint", nullable = false)
    private String identityFingerprint;

    /** The gap's pipeline type token, e.g. "nexus_letter" (legacy rows: "Nexus"). */
    @Column(name = "gap_type", nullable = false)
    private String gapType;

    /** The gap's triad leg ("diagnosis"|"in_service"|"nexus"|"severity"), null when absent. */
    @Column(name = "triad_leg")
    private String triadLeg;

    /** "open" | "resolved" | "dismissed" — see UserGapStateService status constants. */
    @Column(nullable = false, length = 32)
    private String status;

    /** Optional veteran note (future UI affordance; nullable). */
    @Column(columnDefinition = "text")
    private String note;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public UserGapState() {
    }

    public UserGapState(Long claimId, String identityFingerprint, String gapType,
                        String triadLeg, String status) {
        this.claimId = claimId;
        this.identityFingerprint = identityFingerprint;
        this.gapType = gapType;
        this.triadLeg = triadLeg;
        this.status = status;
        this.updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getClaimId() { return claimId; }
    public void setClaimId(Long claimId) { this.claimId = claimId; }
    public String getIdentityFingerprint() { return identityFingerprint; }
    public void setIdentityFingerprint(String identityFingerprint) { this.identityFingerprint = identityFingerprint; }
    public String getGapType() { return gapType; }
    public void setGapType(String gapType) { this.gapType = gapType; }
    public String getTriadLeg() { return triadLeg; }
    public void setTriadLeg(String triadLeg) { this.triadLeg = triadLeg; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
