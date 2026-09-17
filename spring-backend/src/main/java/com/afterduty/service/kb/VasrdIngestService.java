package com.afterduty.service.kb;

import com.afterduty.model.Chunk;
import com.afterduty.model.KbSection;
import com.afterduty.model.VasrdRecord;
import com.afterduty.repository.ChunkRepository;
import com.afterduty.repository.KbSectionRepository;
import com.afterduty.repository.VasrdRecordRepository;
import com.afterduty.service.rag.ChunkingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ingest one 38 CFR section from eCFR Part XML (Increment 7, spec §C.2/§C.3).
 *
 * <p>Per section, idempotently, in one transaction:
 * <ol>
 *   <li><b>Structured records</b> — walk the section's GPOTABLE rating tables →
 *       {@link VasrdRecord} rows (delete-then-insert per cfr_section). Heterogeneous
 *       Part-4 table shapes are parsed <b>defensively</b>: a section whose table fails
 *       to parse logs ONE warn and still gets RAG chunks (criteria stay reachable via
 *       retrieval; the deterministic lookup just lacks that DC until the parser learns
 *       the shape).</li>
 *   <li><b>RAG chunks</b> — section text → {@link ChunkingService} → {@link Chunk} rows
 *       (scope=kb, cfr_section, source=ecfr, as_of_date, section_path = heading);
 *       delete-by-(kb_source, cfr_section) then insert; embed.</li>
 *   <li><b>Watermark</b> — upsert the {@link KbSection}.</li>
 * </ol>
 */
@Service
public class VasrdIngestService {

    private static final Logger log = LoggerFactory.getLogger(VasrdIngestService.class);

    /** A 4-digit diagnostic code as it appears in the schedule. */
    private static final Pattern DC_CODE = Pattern.compile("\\b(\\d{4})\\b");
    /** A rating percentage cell, e.g. "60" or "60 percent". */
    private static final Pattern RATING_PCT = Pattern.compile("^\\s*(\\d{1,3})\\s*$");

    private final VasrdRecordRepository vasrdRecordRepository;
    private final ChunkRepository chunkRepository;
    private final KbSectionRepository kbSectionRepository;
    private final ChunkingService chunkingService;

    public VasrdIngestService(VasrdRecordRepository vasrdRecordRepository,
                              ChunkRepository chunkRepository,
                              KbSectionRepository kbSectionRepository,
                              ChunkingService chunkingService) {
        this.vasrdRecordRepository = vasrdRecordRepository;
        this.chunkRepository = chunkRepository;
        this.kbSectionRepository = kbSectionRepository;
        this.chunkingService = chunkingService;
    }

    /** Outcome of one section ingest — feeds the job summary log. */
    public record SectionResult(String sectionIdentifier, boolean recordsParsed,
                                int recordCount, int chunkCount) {}

    /**
     * Ingest one section. {@code kbSource} is {@code vasrd} (Part 4) or
     * {@code presumptives} (Part 3). Returns the parse outcome. Never throws on a
     * malformed table — only on a missing section node.
     */
    @Transactional
    public SectionResult ingestSection(int part, String kbSource, String sectionIdentifier,
                                       Document partXml, LocalDate asOfDate) {
        Element section = findSection(partXml, sectionIdentifier);
        if (section == null) {
            throw new IllegalArgumentException(
                    "section " + sectionIdentifier + " not found in part " + part + " XML");
        }

        String heading = sectionHeading(section, sectionIdentifier);
        String sectionPath = "[38 CFR § " + sectionIdentifier + " — " + heading + "] ";

        // 1. Structured records (Part 4 rating tables only). Defensive.
        int recordCount = 0;
        boolean recordsParsed = false;
        if (part == 4) {
            try {
                List<VasrdRecord> records = parseRatingRecords(section, sectionIdentifier, heading, asOfDate);
                vasrdRecordRepository.deleteByCfrSection(sectionIdentifier);
                if (!records.isEmpty()) {
                    vasrdRecordRepository.saveAll(records);
                    recordCount = records.size();
                    recordsParsed = true;
                }
            } catch (Exception e) {
                // ONE warn; chunks still happen below so criteria stay retrievable.
                log.warn("VASRD table parse failed for § {} — chunks only: {}",
                        sectionIdentifier, e.getMessage());
            }
        }

        // 2. RAG chunks.
        String sectionText = textContent(section);
        List<ChunkingService.ChunkText> texts =
                chunkingService.chunk(sectionText, sectionPath, List.of());
        chunkRepository.deleteByKbSourceAndCfrSection(kbSource, sectionIdentifier);
        List<Chunk> saved = new ArrayList<>(texts.size());
        for (ChunkingService.ChunkText ct : texts) {
            Chunk chunk = Chunk.builder()
                    .scope("kb")
                    .kbSource(kbSource)
                    .cfrSection(sectionIdentifier)
                    .docType("regulation")
                    .source("ecfr")
                    .asOfDate(asOfDate)
                    .sectionPath(sectionPath.strip())
                    .content(ct.content())
                    .tokenCount(ct.tokenCount())
                    .embeddingStatus("pending")
                    .build();
            saved.add(chunkRepository.save(chunk));
        }
        // NOTE (adversarial-review minor): we do NOT call the blocking Vertex embed
        // here. ingestSection is @Transactional, and the evidence path (§B.3) is
        // explicit that no Vertex :predict HTTP call may run inside an open DB
        // transaction (it uses an afterCommit virtual thread for exactly this reason).
        // For KB, embedding a section synchronously would hold a Postgres connection
        // open for the full external round-trip (up to the 60 s timeout) per section.
        // Instead the chunks are persisted with embedding_status='pending' (above) and
        // ChunkBackfillJob Pass 2 embeds them out-of-band — its
        // findByEmbeddingStatusAndCreatedAtBeforeOrderByCreatedAtAsc query is
        // scope-agnostic, so it picks up these scope='kb' chunks automatically.

        // 3. Watermark upsert.
        KbSection watermark = kbSectionRepository
                .findByPartAndSectionIdentifier(part, sectionIdentifier)
                .orElseGet(KbSection::new);
        watermark.setPart(part);
        watermark.setSectionIdentifier(sectionIdentifier);
        watermark.setLastIssueDate(asOfDate);
        watermark.setContentHash(sha256(sectionText));
        watermark.setLastIngestedAt(Instant.now());
        kbSectionRepository.save(watermark);

        return new SectionResult(sectionIdentifier, recordsParsed, recordCount, saved.size());
    }

    // -------------------------------------------------------------------------
    // XML navigation (JDK DOM — no new dependency)
    // -------------------------------------------------------------------------

    /** Locate the DIV8 node whose N attribute matches the section identifier. */
    Element findSection(Document partXml, String sectionIdentifier) {
        NodeList div8s = partXml.getElementsByTagName("DIV8");
        for (int i = 0; i < div8s.getLength(); i++) {
            Element el = (Element) div8s.item(i);
            String n = el.getAttribute("N");
            if (sectionIdentifier.equals(n)) return el;
        }
        return null;
    }

    private String sectionHeading(Element section, String fallback) {
        NodeList heads = section.getElementsByTagName("HEAD");
        if (heads.getLength() > 0) {
            String h = heads.item(0).getTextContent();
            if (h != null && !h.isBlank()) {
                // Strip a leading "§ 4.71a" prefix so the heading is just the title.
                return h.replaceFirst("^\\s*§?\\s*[\\d.a-zA-Z-]+\\s*", "").strip();
            }
        }
        return fallback;
    }

    private String textContent(Element section) {
        String raw = section.getTextContent();
        if (raw == null) return "";
        // Collapse runaway whitespace so chunk sizing is stable.
        return raw.replaceAll("[ \\t]+", " ").replaceAll("\\n{3,}", "\n\n").strip();
    }

    // -------------------------------------------------------------------------
    // Defensive rating-table parse
    // -------------------------------------------------------------------------

    /**
     * Walk each GPOTABLE's ROW/ENT cells, emitting a {@link VasrdRecord} per
     * (dc_code, rating tier). The schedule lists a DC code + condition title on a
     * lead row, then per-tier criteria + a rating percentage. Defensive: any row that
     * does not yield a usable (dc, pct/criteria) is skipped rather than aborting.
     */
    List<VasrdRecord> parseRatingRecords(Element section, String sectionIdentifier,
                                         String bodySystem, LocalDate asOfDate) {
        List<VasrdRecord> records = new ArrayList<>();
        NodeList tables = section.getElementsByTagName("GPOTABLE");
        int order = 0;
        String currentDc = null;
        String currentTitle = null;

        for (int t = 0; t < tables.getLength(); t++) {
            Element table = (Element) tables.item(t);
            NodeList rows = table.getElementsByTagName("ROW");
            for (int r = 0; r < rows.getLength(); r++) {
                List<String> cells = rowCells((Element) rows.item(r));
                if (cells.isEmpty()) continue;

                // A DC code in the row updates the "current" diagnostic context.
                String dcInRow = firstDcCode(cells);
                if (dcInRow != null) {
                    currentDc = dcInRow;
                    currentTitle = conditionTitle(cells, dcInRow);
                }
                if (currentDc == null) continue;

                Integer pct = firstRatingPct(cells);
                String criteria = criteriaText(cells);
                // Emit a record when this row carries a rating tier OR is the lead row
                // (pct may be null for note-only rows — still useful as criteria text).
                if (pct != null || (criteria != null && !criteria.isBlank())) {
                    records.add(VasrdRecord.builder()
                            .dcCode(currentDc)
                            .title(currentTitle)
                            .bodySystem(bodySystem)
                            .cfrSection(sectionIdentifier)
                            .ratingPct(pct)
                            .criteriaText(criteria)
                            .asOfDate(asOfDate)
                            .displayOrder(order++)
                            .build());
                }
            }
        }
        return records;
    }

    private List<String> rowCells(Element row) {
        List<String> cells = new ArrayList<>();
        NodeList children = row.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node c = children.item(i);
            if (c.getNodeType() == Node.ELEMENT_NODE && "ENT".equals(c.getNodeName())) {
                String text = c.getTextContent();
                cells.add(text == null ? "" : text.replaceAll("\\s+", " ").strip());
            }
        }
        return cells;
    }

    private String firstDcCode(List<String> cells) {
        for (String cell : cells) {
            Matcher m = DC_CODE.matcher(cell);
            if (m.find()) return m.group(1);
        }
        return null;
    }

    private String conditionTitle(List<String> cells, String dcCode) {
        // The condition title usually rides in the same cell as (or just after) the DC
        // code — take the longest text cell that isn't a bare rating percentage.
        String best = null;
        for (String cell : cells) {
            String stripped = cell.replace(dcCode, "").strip();
            if (stripped.isBlank() || RATING_PCT.matcher(stripped).matches()) continue;
            if (best == null || stripped.length() > best.length()) best = stripped;
        }
        return best;
    }

    private Integer firstRatingPct(List<String> cells) {
        for (String cell : cells) {
            Matcher m = RATING_PCT.matcher(cell);
            if (m.matches()) {
                int v = Integer.parseInt(m.group(1));
                if (v >= 0 && v <= 100) return v;
            }
        }
        return null;
    }

    private String criteriaText(List<String> cells) {
        // The criteria is the longest non-numeric cell.
        String best = null;
        for (String cell : cells) {
            if (cell.isBlank() || RATING_PCT.matcher(cell).matches() || DC_CODE.matcher(cell).matches()) {
                continue;
            }
            if (best == null || cell.length() > best.length()) best = cell;
        }
        return best;
    }

    private static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
