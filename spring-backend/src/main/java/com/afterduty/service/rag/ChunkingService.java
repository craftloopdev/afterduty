package com.afterduty.service.rag;

import com.afterduty.model.EvidenceItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Deterministic, stateless, no-LLM chunker (Increment 7, spec §B.2).
 *
 * <p>Splits document/regulation text into ~{@code target-tokens} chunks with
 * {@code overlap-tokens} overlap, paragraph/heading-aware, hard-capped at 1,800
 * tokens (inside gemini-embedding-001's ~2,048-token input limit). A contextual
 * header is prepended to every chunk's content (research-gcp §1.7 "contextual
 * retrieval") — what lets answers cite "your March 2019 C&P exam".
 *
 * <p>Token estimate = chars/4. The splitter takes a {@code preferredBreakPatterns}
 * seam so doc-type-specific section splitting (decision-letter headings, DBQ
 * questions, STR encounters) is an additive quality follow-up, not Inc-7 scope.
 */
@Service
public class ChunkingService {

    private static final Logger log = LoggerFactory.getLogger(ChunkingService.class);

    /** chars/4 token estimate. */
    private static final int CHARS_PER_TOKEN = 4;

    /** Hard cap per chunk (tokens) — stays inside the ~2,048-token model input limit. */
    private static final int HARD_CAP_TOKENS = 1_800;

    private final int targetTokens;
    private final int overlapTokens;

    public ChunkingService(
            @Value("${va-claim.rag.chunking.target-tokens:600}") int targetTokens,
            @Value("${va-claim.rag.chunking.overlap-tokens:120}") int overlapTokens) {
        this.targetTokens = targetTokens;
        this.overlapTokens = overlapTokens;
    }

    /** One produced chunk: the content (header already prepended) and its token estimate. */
    public record ChunkText(String content, int tokenCount) {}

    /** chars/4 token estimate. */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        return Math.max(1, text.length() / CHARS_PER_TOKEN);
    }

    // -------------------------------------------------------------------------
    // Evidence entry point
    // -------------------------------------------------------------------------

    /**
     * Chunk a processed evidence document. Text source is {@code rawContent}; when
     * absent/blank (pure-multimodal docs) it falls back to {@code aiSummary}; when
     * both are absent it produces zero chunks (logged at DEBUG).
     */
    public List<ChunkText> chunkEvidence(EvidenceItem evidence) {
        String text = evidence.getRawContent();
        if (text == null || text.isBlank()) {
            text = evidence.getAiSummary();
        }
        if (text == null || text.isBlank()) {
            log.debug("ChunkingService: evidence {} has no rawContent/aiSummary — zero chunks",
                    evidence.getId());
            return List.of();
        }
        String header = evidenceHeader(evidence);
        return chunk(text, header, List.of());
    }

    /**
     * The contextual header for an evidence chunk:
     * {@code "[{filename} — {aiClassification or 'document'}{, doc_date if known}] "}.
     */
    public String evidenceHeader(EvidenceItem evidence) {
        String filename = evidence.getFilename() != null ? evidence.getFilename() : "document";
        String klass = evidence.getAiClassification() != null && !evidence.getAiClassification().isBlank()
                ? evidence.getAiClassification() : "document";
        String docDate = evidenceDocDate(evidence);
        StringBuilder h = new StringBuilder("[").append(filename).append(" — ").append(klass);
        if (docDate != null && !docDate.isBlank()) {
            h.append(", ").append(docDate);
        }
        return h.append("] ").toString();
    }

    /**
     * Best-effort doc_date from aiExtractedData's {@code document_date} key — same
     * loose-string convention as {@code Atom.timestamp}. Null when absent.
     */
    public String evidenceDocDate(EvidenceItem evidence) {
        Map<String, Object> extracted = evidence.getAiExtractedData();
        if (extracted == null) return null;
        Object v = extracted.get("document_date");
        return v == null ? null : v.toString();
    }

    // -------------------------------------------------------------------------
    // Core splitter — text + header + optional preferred break patterns
    // -------------------------------------------------------------------------

    /**
     * Recursive paragraph/heading-aware split of {@code body} into ~target-token
     * windows with overlap, each prefixed with {@code header}. {@code header} is
     * counted toward the token budget so chunks stay inside the model limit.
     */
    public List<ChunkText> chunk(String body, String header, List<String> preferredBreakPatterns) {
        List<ChunkText> out = new ArrayList<>();
        if (body == null || body.isBlank()) return out;

        String safeHeader = header == null ? "" : header;
        int headerTokens = estimateTokens(safeHeader);
        // Body budget = target minus the header it will carry, floored sanely.
        int bodyTarget = Math.max(50, targetTokens - headerTokens);
        int bodyCap = Math.max(bodyTarget, HARD_CAP_TOKENS - headerTokens);
        int targetChars = bodyTarget * CHARS_PER_TOKEN;
        int capChars = bodyCap * CHARS_PER_TOKEN;
        int overlapChars = Math.min(overlapTokens * CHARS_PER_TOKEN, targetChars / 2);

        // 1. Split into atomic segments at the coarsest natural boundary first
        //    (preferred patterns, then blank-line paragraphs), then sentences, then
        //    a hard char cut — recursive splitter, biggest break wins.
        List<String> segments = splitToSegments(body, preferredBreakPatterns, capChars);

        // 2. Greedily pack segments into target-sized windows with char overlap.
        StringBuilder window = new StringBuilder();
        for (String seg : segments) {
            if (window.length() > 0 && window.length() + seg.length() > targetChars) {
                out.add(emit(safeHeader, window.toString()));
                window = new StringBuilder(tail(window.toString(), overlapChars));
            }
            if (window.length() > 0 && !endsWithWhitespace(window)) window.append(' ');
            window.append(seg);
            // A single oversized segment that itself blew the cap is already hard-cut
            // by splitToSegments, so this only flushes when packing crosses the cap.
            if (window.length() >= capChars) {
                out.add(emit(safeHeader, window.toString()));
                window = new StringBuilder(tail(window.toString(), overlapChars));
            }
        }
        if (window.length() > 0 && !window.toString().isBlank()) {
            out.add(emit(safeHeader, window.toString()));
        }
        return out;
    }

    private ChunkText emit(String header, String body) {
        String content = header + body.strip();
        return new ChunkText(content, estimateTokens(content));
    }

    /**
     * Recursive split: first on preferred patterns, then blank-line paragraphs,
     * then sentence boundaries, finally a hard char window — so no segment exceeds
     * {@code capChars}.
     */
    private List<String> splitToSegments(String text, List<String> preferredBreakPatterns, int capChars) {
        List<String> coarse = new ArrayList<>();

        // Preferred patterns (additive seam): split keeping the delimiter line.
        List<String> working = new ArrayList<>();
        working.add(text);
        if (preferredBreakPatterns != null) {
            for (String pattern : preferredBreakPatterns) {
                List<String> next = new ArrayList<>();
                for (String piece : working) {
                    next.addAll(splitKeepingDelimiter(piece, pattern));
                }
                working = next;
            }
        }

        // Paragraphs (blank-line separated) within each preferred segment.
        for (String piece : working) {
            for (String para : piece.split("\\n\\s*\\n")) {
                if (!para.isBlank()) coarse.add(para.strip());
            }
        }
        if (coarse.isEmpty() && !text.isBlank()) coarse.add(text.strip());

        // Any coarse segment over the cap → sentence split → hard char cut.
        List<String> fine = new ArrayList<>();
        for (String seg : coarse) {
            if (seg.length() <= capChars) {
                fine.add(seg);
                continue;
            }
            for (String sentence : seg.split("(?<=[.!?])\\s+")) {
                if (sentence.length() <= capChars) {
                    if (!sentence.isBlank()) fine.add(sentence.strip());
                } else {
                    fine.addAll(hardWindows(sentence, capChars));
                }
            }
        }
        return fine;
    }

    private List<String> splitKeepingDelimiter(String text, String pattern) {
        List<String> parts = new ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(pattern).matcher(text);
        int last = 0;
        while (m.find()) {
            if (m.start() > last) {
                String chunk = text.substring(last, m.start());
                if (!chunk.isBlank()) parts.add(chunk);
                last = m.start();
            }
        }
        if (last < text.length()) {
            String chunk = text.substring(last);
            if (!chunk.isBlank()) parts.add(chunk);
        }
        return parts.isEmpty() ? List.of(text) : parts;
    }

    private List<String> hardWindows(String text, int capChars) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < text.length(); i += capChars) {
            out.add(text.substring(i, Math.min(text.length(), i + capChars)).strip());
        }
        return out;
    }

    private String tail(String s, int overlapChars) {
        if (overlapChars <= 0 || s.length() <= overlapChars) return "";
        // Start the overlap at a word boundary for cleaner context.
        String slice = s.substring(s.length() - overlapChars);
        int sp = slice.indexOf(' ');
        return sp > 0 ? slice.substring(sp + 1) : slice;
    }

    private boolean endsWithWhitespace(CharSequence cs) {
        return cs.length() > 0 && Character.isWhitespace(cs.charAt(cs.length() - 1));
    }
}
