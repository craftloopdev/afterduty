package com.afterduty.job;

import com.afterduty.repository.KbSectionRepository;
import com.afterduty.service.kb.EcfrClient;
import com.afterduty.service.kb.PresumptiveIngest;
import com.afterduty.service.kb.VasrdIngestService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Nightly eCFR refresh (Increment 7, spec §C.5) — keeps the KB current with one
 * INFO summary per run and per-section failure isolation.
 *
 * <ol>
 *   <li><b>Cold start</b> — empty {@code kb_sections} → full bootstrap (Part 4: all
 *       DIV8 sections; Part 3: the {@link PresumptiveIngest#SECTIONS} allowlist) at the
 *       title's {@code up_to_date_as_of}. Also kicked once on first deploy via
 *       {@link ApplicationReadyEvent} on a background virtual thread (so a fresh env
 *       doesn't wait a day); skipped when non-empty.</li>
 *   <li><b>Diff</b> — title 38 {@code up_to_date_as_of} ≤ watermark → DEBUG + exit;
 *       else fetch changed sections per part and re-ingest only those.</li>
 * </ol>
 */
@Component
public class KbRefreshJob {

    private static final Logger log = LoggerFactory.getLogger(KbRefreshJob.class);

    private final EcfrClient ecfrClient;
    private final VasrdIngestService vasrdIngestService;
    private final KbSectionRepository kbSectionRepository;

    @Value("${va-claim.kb.enabled:true}")
    private boolean kbEnabled;

    public KbRefreshJob(EcfrClient ecfrClient,
                        VasrdIngestService vasrdIngestService,
                        KbSectionRepository kbSectionRepository) {
        this.ecfrClient = ecfrClient;
        this.vasrdIngestService = vasrdIngestService;
        this.kbSectionRepository = kbSectionRepository;
    }

    /** First-deploy kick so a fresh environment doesn't wait until 09:30 UTC. */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!kbEnabled) return;
        if (kbSectionRepository.count() > 0) return;  // already bootstrapped
        Thread.ofVirtual().name("kb-bootstrap").start(() -> {
            try {
                refresh();
            } catch (Exception e) {
                log.warn("KbRefreshJob bootstrap failed: {}", e.getMessage());
            }
        });
    }

    @Scheduled(cron = "${va-claim.kb.refresh-cron:0 30 9 * * *}", zone = "UTC")
    public void scheduled() {
        if (!kbEnabled) return;
        refresh();
    }

    /** The refresh algorithm — cold start full ingest, else diff and re-ingest changed sections. */
    public void refresh() {
        EcfrClient.TitleFreshness freshness = ecfrClient.fetchTitleFreshness();
        LocalDate asOf = freshness.upToDateAsOf();
        if (asOf == null) {
            log.warn("KbRefreshJob: title 38 has no up_to_date_as_of — skipping");
            return;
        }

        boolean coldStart = kbSectionRepository.count() == 0;
        LocalDate watermark = coldStart ? null : kbSectionRepository.findMaxLastIssueDate();

        if (!coldStart && watermark != null && !asOf.isAfter(watermark)) {
            log.debug("KbRefreshJob: title 38 unchanged (asOf={} ≤ watermark={}) — exit", asOf, watermark);
            return;
        }

        int checked = 0;
        int reingested = 0;
        int tableFailures = 0;

        // Part 4 (VASRD) and Part 3 (presumptives).
        for (int part : new int[]{4, 3}) {
            // Adversarial-review minor: fetch + DOM-parse each part XML AT MOST ONCE per
            // refresh. The 1.06 MB Part-4 XML used to be fetched+parsed twice on cold start
            // (once to enumerate DIV8 ids in bootstrapSections, once here to ingest) —
            // double the eCFR bandwidth + parse cost against an endpoint with unpublished
            // rate limits the class is asked to be polite to. We now lazily parse once and
            // reuse the same Document for both enumeration and ingest.
            Document partXml = null;
            List<String> sections;
            if (coldStart) {
                partXml = parseXml(ecfrClient.fetchPartXml(part, asOf));
                sections = bootstrapSections(part, partXml);
            } else {
                sections = changedSections(part, watermark);
            }
            if (sections.isEmpty()) continue;

            // Diff path: only fetch the XML now that we know there are sections to ingest.
            if (partXml == null) {
                partXml = parseXml(ecfrClient.fetchPartXml(part, asOf));
            }
            String kbSource = part == 4 ? "vasrd" : "presumptives";

            for (String sectionId : sections) {
                checked++;
                try {
                    VasrdIngestService.SectionResult result =
                            vasrdIngestService.ingestSection(part, kbSource, sectionId, partXml, asOf);
                    reingested++;
                    if (part == 4 && !result.recordsParsed()) tableFailures++;
                } catch (Exception e) {
                    // Per-section isolation: one bad section never aborts the run; it
                    // retries next night (watermark advanced only per ingested section).
                    log.warn("KbRefreshJob: section § {} (part {}) failed: {}",
                            sectionId, part, e.getMessage());
                }
            }
        }

        log.info("KbRefreshJob: checked {} section(s), re-ingested {}, table-parse failures {}",
                checked, reingested, tableFailures);
    }

    /**
     * Cold-start section list: Part 4 = every DIV8 in the part; Part 3 = the allowlist.
     * Takes the already-parsed {@code partXml} (fetched once by {@link #refresh}) so the
     * 1.06 MB Part-4 XML is never fetched/parsed twice per cold-start refresh.
     */
    private List<String> bootstrapSections(int part, Document partXml) {
        if (part == 3) return PresumptiveIngest.SECTIONS;
        // Part 4 — enumerate every DIV8 section identifier in the already-parsed XML.
        Set<String> ids = new LinkedHashSet<>();
        var div8s = partXml.getElementsByTagName("DIV8");
        for (int i = 0; i < div8s.getLength(); i++) {
            org.w3c.dom.Element el = (org.w3c.dom.Element) div8s.item(i);
            String n = el.getAttribute("N");
            if (n != null && !n.isBlank()) ids.add(n);
        }
        return new ArrayList<>(ids);
    }

    /** Diff section list: versions amended since the watermark, intersected with our scope. */
    private List<String> changedSections(int part, LocalDate watermark) {
        List<EcfrClient.SectionVersion> versions = ecfrClient.fetchVersions(part, watermark);
        Set<String> changed = new LinkedHashSet<>();
        for (EcfrClient.SectionVersion v : versions) {
            String id = v.sectionIdentifier();
            if (part == 3 && !PresumptiveIngest.SECTIONS.contains(id)) continue;  // only our backbone
            changed.add(id);
        }
        return new ArrayList<>(changed);
    }

    /** Parse a part XML string into a DOM, hardened against XXE. */
    static Document parseXml(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(new InputSource(new StringReader(xml)));
        } catch (Exception e) {
            throw new RuntimeException("failed to parse eCFR part XML: " + e.getMessage(), e);
        }
    }
}
