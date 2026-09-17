package com.afterduty.model;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import static jakarta.persistence.FetchType.LAZY;

@Entity
@Table(name = "intake_messages")
public class IntakeMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "claim_id", nullable = false)
    private Long claimId;

    @Column(name = "thread_id", nullable = false)
    private Long threadId;

    @Column(nullable = false)
    private String role;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "extracted_data", columnDefinition = "text")
    private Map<String, Object> extractedData;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "claim_id", insertable = false, updatable = false)
    private Claim claim;

    @ManyToOne(fetch = LAZY)
    @JoinColumn(name = "thread_id", insertable = false, updatable = false)
    private ChatThread thread;

    @OneToMany(mappedBy = "message", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<Atom> atoms = new ArrayList<>();

    public IntakeMessage() {
    }

    public IntakeMessage(Long id, Long claimId, Long threadId, String role, String content,
                         Map<String, Object> extractedData, Instant createdAt,
                         Claim claim, ChatThread thread, List<Atom> atoms) {
        this.id = id;
        this.claimId = claimId;
        this.threadId = threadId;
        this.role = role;
        this.content = content;
        this.extractedData = extractedData;
        this.createdAt = createdAt;
        this.claim = claim;
        this.thread = thread;
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

    public Long getThreadId() {
        return threadId;
    }

    public void setThreadId(Long threadId) {
        this.threadId = threadId;
    }

    public ChatThread getThread() {
        return thread;
    }

    public void setThread(ChatThread thread) {
        this.thread = thread;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Map<String, Object> getExtractedData() {
        return extractedData;
    }

    public void setExtractedData(Map<String, Object> extractedData) {
        this.extractedData = extractedData;
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
        IntakeMessage that = (IntakeMessage) o;
        return Objects.equals(id, that.id) &&
                Objects.equals(claimId, that.claimId) &&
                Objects.equals(role, that.role) &&
                Objects.equals(content, that.content) &&
                Objects.equals(extractedData, that.extractedData) &&
                Objects.equals(createdAt, that.createdAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, claimId, role, content, extractedData, createdAt);
    }

    @Override
    public String toString() {
        return "IntakeMessage(" +
                "id=" + id +
                ", claimId=" + claimId +
                ", role=" + role +
                ", content=" + content +
                ", extractedData=" + extractedData +
                ", createdAt=" + createdAt +
                ')';
    }

    public static IntakeMessageBuilder builder() {
        return new IntakeMessageBuilder();
    }

    public static class IntakeMessageBuilder {
        private Long id;
        private Long claimId;
        private Long threadId;
        private String role;
        private String content;
        private Map<String, Object> extractedData;
        private Instant createdAt = Instant.now();
        private Claim claim;
        private ChatThread thread;
        private List<Atom> atoms = new ArrayList<>();

        IntakeMessageBuilder() {
        }

        public IntakeMessageBuilder id(Long id) {
            this.id = id;
            return this;
        }

        public IntakeMessageBuilder claimId(Long claimId) {
            this.claimId = claimId;
            return this;
        }

        public IntakeMessageBuilder threadId(Long threadId) {
            this.threadId = threadId;
            return this;
        }

        public IntakeMessageBuilder role(String role) {
            this.role = role;
            return this;
        }

        public IntakeMessageBuilder content(String content) {
            this.content = content;
            return this;
        }

        public IntakeMessageBuilder extractedData(Map<String, Object> extractedData) {
            this.extractedData = extractedData;
            return this;
        }

        public IntakeMessageBuilder createdAt(Instant createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public IntakeMessageBuilder claim(Claim claim) {
            this.claim = claim;
            return this;
        }

        public IntakeMessageBuilder thread(ChatThread thread) {
            this.thread = thread;
            return this;
        }

        public IntakeMessageBuilder atoms(List<Atom> atoms) {
            this.atoms = atoms;
            return this;
        }

        public IntakeMessage build() {
            return new IntakeMessage(id, claimId, threadId, role, content, extractedData, createdAt, claim, thread, atoms);
        }
    }
}
