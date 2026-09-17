package com.afterduty.service.kb;

import com.afterduty.model.Chunk;
import com.afterduty.model.KbSection;
import com.afterduty.model.VasrdRecord;
import com.afterduty.repository.ChunkRepository;
import com.afterduty.repository.KbSectionRepository;
import com.afterduty.repository.VasrdRecordRepository;
import com.afterduty.service.rag.ChunkingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.w3c.dom.Document;

import java.io.StringReader;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * VASRD/presumptives section ingest from eCFR Part XML (spec §C.2/§C.3, §H.1):
 *
 * <ul>
 *   <li>a well-formed GPOTABLE ⇒ expected {@link VasrdRecord} rows (dc/pct/criteria/as_of);</li>
 *   <li>KB chunks carry {@code cfr_section} + {@code as_of_date} and the section heading;</li>
 *   <li>a malformed/table-less section ⇒ chunks only (no records, no throw);</li>
 *   <li>delete-then-insert per cfr_section; the watermark is upserted.</li>
 * </ul>
 */
@Tag("regression")
class VasrdIngestServiceTest {

    private static final LocalDate AS_OF = LocalDate.parse("2026-06-09");

    private VasrdRecordRepository vasrdRepo;
    private ChunkRepository chunkRepo;
    private KbSectionRepository kbSectionRepo;
    private VasrdIngestService service;

    private Document part4;
    private Document part3;

    @BeforeEach
    void setup() throws Exception {
        vasrdRepo = mock(VasrdRecordRepository.class);
        chunkRepo = mock(ChunkRepository.class);
        kbSectionRepo = mock(KbSectionRepository.class);
        ChunkingService chunking = new ChunkingService(600, 120);
        service = new VasrdIngestService(vasrdRepo, chunkRepo, kbSectionRepo, chunking);

        when(chunkRepo.save(any(Chunk.class))).thenAnswer(inv -> inv.getArgument(0));
        when(kbSectionRepo.findByPartAndSectionIdentifier(any(), anyString()))
                .thenReturn(Optional.empty());

        part4 = parse(FakeEcfrClient.fixture("part4-sample.xml"));
        part3 = parse(FakeEcfrClient.fixture("part3-sample.xml"));
    }

    @Test
    void wellFormedTable_emitsVasrdRecords_withPctCriteriaAndAsOf() {
        VasrdIngestService.SectionResult result =
                service.ingestSection(4, "vasrd", "4.71a", part4, AS_OF);

        assertThat(result.recordsParsed()).isTrue();
        assertThat(result.recordCount()).isGreaterThan(0);

        // delete-then-insert per cfr_section.
        verify(vasrdRepo).deleteByCfrSection("4.71a");
        ArgumentCaptor<List<VasrdRecord>> records = ArgumentCaptor.forClass(List.class);
        verify(vasrdRepo).saveAll(records.capture());

        List<VasrdRecord> rows = records.getValue();
        // DC 5260 is present with the expected rating tiers (30/20/10/0).
        assertThat(rows).anyMatch(r -> "5260".equals(r.getDcCode()) && Integer.valueOf(30).equals(r.getRatingPct()));
        assertThat(rows).anyMatch(r -> "5260".equals(r.getDcCode()) && Integer.valueOf(0).equals(r.getRatingPct()));
        // Every row stamped with the section + as_of date.
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.getCfrSection()).isEqualTo("4.71a");
            assertThat(r.getAsOfDate()).isEqualTo(AS_OF);
        });
        // Criteria text survived (e.g. the flexion tiers).
        assertThat(rows).anyMatch(r -> r.getCriteriaText() != null && r.getCriteriaText().contains("Flexion"));
    }

    @Test
    void wellFormedTable_alsoWritesKbChunks_withCfrSectionAndAsOf() {
        service.ingestSection(4, "vasrd", "4.71a", part4, AS_OF);

        verify(chunkRepo).deleteByKbSourceAndCfrSection("vasrd", "4.71a");
        ArgumentCaptor<Chunk> chunk = ArgumentCaptor.forClass(Chunk.class);
        verify(chunkRepo, org.mockito.Mockito.atLeastOnce()).save(chunk.capture());

        Chunk c = chunk.getValue();
        assertThat(c.getScope()).isEqualTo("kb");
        assertThat(c.getKbSource()).isEqualTo("vasrd");
        assertThat(c.getCfrSection()).isEqualTo("4.71a");
        assertThat(c.getAsOfDate()).isEqualTo(AS_OF);
        assertThat(c.getSource()).isEqualTo("ecfr");
        assertThat(c.getDocType()).isEqualTo("regulation");
        assertThat(c.getSectionPath()).contains("38 CFR § 4.71a");
        // Adversarial-review minor: ingestSection (@Transactional) must NOT make the
        // blocking Vertex embed call inside the open DB transaction. Chunks are persisted
        // as 'pending' and ChunkBackfillJob Pass 2 embeds them out-of-band (its
        // findByEmbeddingStatus query is scope-agnostic, so it picks up scope='kb' chunks).
        assertThat(c.getEmbeddingStatus()).isEqualTo("pending");
    }

    @Test
    void tablelessSection_producesChunksOnly_noRecords_noThrow() {
        VasrdIngestService.SectionResult result =
                service.ingestSection(4, "vasrd", "4.10", part4, AS_OF);

        assertThat(result.recordsParsed()).isFalse();
        assertThat(result.recordCount()).isZero();
        // Chunks still written so the criteria stay retrievable.
        assertThat(result.chunkCount()).isGreaterThan(0);
        // deleteByCfrSection still runs (idempotent clear), but no rows saved.
        verify(vasrdRepo).deleteByCfrSection("4.10");
        verify(vasrdRepo, never()).saveAll(any());
    }

    @Test
    void malformedTable_producesChunksOnly_noRecords_noThrow() {
        // § 4.150 has a GPOTABLE with no parseable DC/pct cells.
        VasrdIngestService.SectionResult result =
                service.ingestSection(4, "vasrd", "4.150", part4, AS_OF);

        assertThat(result.recordsParsed()).isFalse();
        assertThat(result.recordCount()).isZero();
        assertThat(result.chunkCount()).isGreaterThan(0);
    }

    @Test
    void presumptiveSection_part3_writesKbChunks_noVasrdRecords() {
        VasrdIngestService.SectionResult result =
                service.ingestSection(3, "presumptives", "3.307", part3, AS_OF);

        // Part 3 never parses rating records.
        verify(vasrdRepo, never()).deleteByCfrSection(anyString());
        verify(vasrdRepo, never()).saveAll(any());
        assertThat(result.chunkCount()).isGreaterThan(0);

        ArgumentCaptor<Chunk> chunk = ArgumentCaptor.forClass(Chunk.class);
        verify(chunkRepo, org.mockito.Mockito.atLeastOnce()).save(chunk.capture());
        assertThat(chunk.getValue().getKbSource()).isEqualTo("presumptives");
        assertThat(chunk.getValue().getCfrSection()).isEqualTo("3.307");
    }

    @Test
    void watermarkIsUpserted_withPartSectionAsOfAndHash() {
        service.ingestSection(4, "vasrd", "4.71a", part4, AS_OF);

        ArgumentCaptor<KbSection> wm = ArgumentCaptor.forClass(KbSection.class);
        verify(kbSectionRepo).save(wm.capture());
        KbSection w = wm.getValue();
        assertThat(w.getPart()).isEqualTo(4);
        assertThat(w.getSectionIdentifier()).isEqualTo("4.71a");
        assertThat(w.getLastIssueDate()).isEqualTo(AS_OF);
        assertThat(w.getContentHash()).isNotBlank();
        assertThat(w.getLastIngestedAt()).isNotNull();
    }

    @Test
    void missingSection_throws() {
        assertThatThrownBy(() -> service.ingestSection(4, "vasrd", "9.999", part4, AS_OF))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Document parse(String xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return f.newDocumentBuilder().parse(new org.xml.sax.InputSource(new StringReader(xml)));
    }
}
