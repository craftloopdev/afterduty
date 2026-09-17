package com.afterduty.service.rag;

import com.afterduty.model.EvidenceItem;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deterministic chunker coverage (spec §H.1): sizes/overlap, the contextual header,
 * the hard token cap, and the empty-input contract. Pure — no Spring, no LLM.
 */
@Tag("regression")
class ChunkingServiceTest {

    /** target=600 tokens (~2,400 chars), overlap=120 tokens (~480 chars). */
    private final ChunkingService chunking = new ChunkingService(600, 120);

    @Test
    void emptyOrBlankBody_producesZeroChunks() {
        assertThat(chunking.chunk("", "[h] ", List.of())).isEmpty();
        assertThat(chunking.chunk("   \n  ", "[h] ", List.of())).isEmpty();
        assertThat(chunking.chunk(null, "[h] ", List.of())).isEmpty();
    }

    @Test
    void shortText_isOneChunk_withHeaderPrepended() {
        List<ChunkingService.ChunkText> chunks =
                chunking.chunk("The veteran reports right knee pain since 2019.", "[knee.pdf — exam] ", List.of());

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).content())
                .startsWith("[knee.pdf — exam] ")
                .contains("right knee pain");
        assertThat(chunks.get(0).tokenCount()).isPositive();
    }

    @Test
    void longText_splitsIntoMultipleChunks_eachUnderTheHardCap() {
        // ~12,000 chars ≈ 3,000 tokens of paragraphs → must split.
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            body.append("Paragraph ").append(i)
                .append(" describes the in-service event and the current symptoms in detail. ")
                .append("It continues with additional clinical observations and dates.\n\n");
        }
        List<ChunkingService.ChunkText> chunks = chunking.chunk(body.toString(), "[str.pdf — service record] ", List.of());

        assertThat(chunks.size()).isGreaterThan(1);
        // Every chunk stays inside the 1,800-token hard cap.
        assertThat(chunks).allSatisfy(c ->
                assertThat(c.tokenCount()).isLessThanOrEqualTo(1_800));
        // Every chunk carries the contextual header.
        assertThat(chunks).allSatisfy(c ->
                assertThat(c.content()).startsWith("[str.pdf — service record] "));
    }

    @Test
    void consecutiveChunks_overlapForContext() {
        // Distinct sentence markers so we can detect carried-over overlap text.
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            body.append("Sentence number ").append(i).append(" about the claim. ");
        }
        List<ChunkingService.ChunkText> chunks = chunking.chunk(body.toString(), "", List.of());
        assertThat(chunks.size()).isGreaterThan(1);

        // The tail of chunk N should appear at the head of chunk N+1 (overlap window).
        String firstBodyTail = chunks.get(0).content();
        String secondBody = chunks.get(1).content();
        String tailToken = firstBodyTail.substring(Math.max(0, firstBodyTail.length() - 40)).strip();
        // At least one overlapping token of the boundary is shared.
        String lastWord = tailToken.substring(tailToken.lastIndexOf(' ') + 1);
        assertThat(secondBody).contains(lastWord);
    }

    @Test
    void singleOversizedParagraph_isHardWindowedUnderTheCap() {
        // One giant paragraph with no blank lines and no sentence breaks → forced hard cut.
        String giant = "x".repeat(40_000);
        List<ChunkingService.ChunkText> chunks = chunking.chunk(giant, "[big.txt — document] ", List.of());

        assertThat(chunks.size()).isGreaterThan(1);
        assertThat(chunks).allSatisfy(c ->
                assertThat(c.tokenCount()).isLessThanOrEqualTo(1_800));
    }

    @Test
    void evidenceHeader_usesFilenameClassificationAndDocDate() {
        EvidenceItem ev = new EvidenceItem();
        ev.setFilename("cp-exam.pdf");
        ev.setAiClassification("C&P exam");
        Map<String, Object> extracted = new HashMap<>();
        extracted.put("document_date", "2019-03-14");
        ev.setAiExtractedData(extracted);

        assertThat(chunking.evidenceHeader(ev))
                .isEqualTo("[cp-exam.pdf — C&P exam, 2019-03-14] ");
        assertThat(chunking.evidenceDocDate(ev)).isEqualTo("2019-03-14");
    }

    @Test
    void evidenceHeader_fallsBackWhenMetadataMissing() {
        EvidenceItem ev = new EvidenceItem();
        // No filename, no classification, no extracted data.
        assertThat(chunking.evidenceHeader(ev)).isEqualTo("[document — document] ");
        assertThat(chunking.evidenceDocDate(ev)).isNull();
    }

    @Test
    void chunkEvidence_fallsBackToAiSummaryWhenRawContentBlank_andZeroWhenBothAbsent() {
        EvidenceItem withSummary = new EvidenceItem();
        withSummary.setFilename("scan.pdf");
        withSummary.setRawContent("   ");
        withSummary.setAiSummary("Summary of a multimodal scan with knee findings.");
        assertThat(chunking.chunkEvidence(withSummary)).isNotEmpty();

        EvidenceItem empty = new EvidenceItem();
        empty.setFilename("blank.pdf");
        assertThat(chunking.chunkEvidence(empty)).isEmpty();
    }
}
