package com.afterduty.service;

import com.afterduty.repository.VasrdRecordRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Diagnostic-code index over 38 CFR Part 4 — condition title, body system, and
 * CFR section for every code in the schedule.
 *
 * <p>Replaces the former {@code MasterlistService}, which read a scrape of a
 * third-party site. Every row here derives from the eCFR versioner API (public
 * domain); see {@code scripts/generate_vasrd_index.py}.
 *
 * <p><b>DB-first, bundled fallback</b> — the same two-tier shape as
 * {@link VasrdDataService}. When {@code vasrd_records} carries the ingested
 * schedule it wins, because it is point-in-time and refreshed nightly. The
 * bundled {@code vasrd_index.json} answers when that table is empty: cold boot,
 * {@code KB_ENABLED=false}, or tests.
 *
 * <p>Its one consumer is {@link com.afterduty.service.gap.EvidenceGapAnalyzer},
 * which renders a short reference block into the gap prompt so the model can
 * place a condition within its body system without seeing the whole schedule.
 */
@Service
public class VasrdIndexService {

    private static final Logger log = LoggerFactory.getLogger(VasrdIndexService.class);

    /** Max sibling codes rendered into a prompt block. */
    private static final int MAX_SIBLINGS = 12;

    private static final String ECFR_SECTION_URL =
            "https://www.ecfr.gov/current/title-38/part-4/section-";

    public record Entry(String code, String title, String bodySystem, String cfrSection) {}

    private final VasrdRecordRepository vasrdRecordRepository;

    private final ObjectMapper mapper = new ObjectMapper();
    private List<Entry> all = List.of();
    private Map<String, Entry> byCode = Map.of();
    private Map<String, List<Entry>> byLoweredTitle = Map.of();
    private String bundledAsOf;

    public VasrdIndexService(VasrdRecordRepository vasrdRecordRepository) {
        this.vasrdRecordRepository = vasrdRecordRepository;
    }

    @PostConstruct
    public void load() {
        try {
            JsonNode root = mapper.readTree(
                    new ClassPathResource("vasrd_index.json").getInputStream());
            bundledAsOf = root.path("as_of").asText(null);

            var entries = new ArrayList<Entry>();
            for (JsonNode n : root.path("codes")) {
                entries.add(new Entry(
                        n.path("code").asText(null),
                        n.path("title").asText(null),
                        n.path("body_system").asText(null),
                        n.path("cfr_section").asText(null)));
            }
            this.all = Collections.unmodifiableList(entries);

            var codes = new HashMap<String, Entry>();
            var titles = new HashMap<String, List<Entry>>();
            for (Entry e : entries) {
                if (e.code != null) codes.putIfAbsent(e.code, e);
                if (e.title != null) {
                    titles.computeIfAbsent(e.title.toLowerCase(Locale.ROOT),
                            k -> new ArrayList<>()).add(e);
                }
            }
            this.byCode = codes;
            this.byLoweredTitle = titles;
            log.info("VASRD index loaded: {} codes, as of {}", entries.size(), bundledAsOf);
        } catch (IOException e) {
            // Non-fatal: the gap prompt simply omits its reference block.
            log.error("Failed to load bundled vasrd_index.json", e);
        }
    }

    public List<Entry> all() { return all; }

    /** Exact 4-digit diagnostic-code lookup. */
    public Entry findByCode(String code) {
        return code == null ? null : byCode.get(code);
    }

    /** Case-insensitive exact title match. Empty when nothing matches. */
    public List<Entry> findByTitle(String title) {
        if (title == null) return List.of();
        return byLoweredTitle.getOrDefault(title.toLowerCase(Locale.ROOT), List.of());
    }

    // ──────────────────────────────────────────────────────────────
    //  Prompt helpers
    // ──────────────────────────────────────────────────────────────

    private volatile String cachedPromptDigest;

    /**
     * The whole schedule as a compact "code — title" listing grouped by body
     * system, for prompts that identify conditions across the full corpus.
     * Cached; the bundled index does not change at runtime.
     */
    public String toPromptDigest() {
        if (cachedPromptDigest != null) return cachedPromptDigest;

        var grouped = new java.util.TreeMap<String, java.util.TreeMap<String, String>>();
        for (Entry e : all) {
            if (e.code == null) continue;
            grouped.computeIfAbsent(displaySystem(e.bodySystem), k -> new java.util.TreeMap<>())
                   .putIfAbsent(e.code, e.title);
        }

        var sb = new StringBuilder(64_000);
        sb.append("Canonical VASRD codes by body system (38 CFR Part 4");
        if (bundledAsOf != null) sb.append(", as of ").append(bundledAsOf);
        sb.append("):\n");
        for (var system : grouped.entrySet()) {
            sb.append('\n').append(system.getKey()).append('\n');
            for (var code : system.getValue().entrySet()) {
                sb.append("  ").append(code.getKey())
                  .append("  ").append(code.getValue()).append('\n');
            }
        }
        cachedPromptDigest = sb.toString();
        return cachedPromptDigest;
    }

    /**
     * A short reference block for one condition: the schedule entry it maps to,
     * a citation to the governing CFR section, and up to {@value #MAX_SIBLINGS}
     * neighbouring codes in the same body system.
     *
     * <p>Rides the volatile tail of the gap prompt, never the cached prefix, so
     * its bytes cannot invalidate the shared prompt cache.
     */
    public String contextForCondition(String name, String vasrdCode, String bodySystem) {
        Entry anchor = resolve(name, vasrdCode);

        var sb = new StringBuilder(1024);
        if (anchor != null) {
            sb.append("Schedule entry: ").append(anchor.title);
            if (anchor.code != null) sb.append(" (VASRD ").append(anchor.code).append(')');
            sb.append(" — ").append(displaySystem(anchor.bodySystem)).append('\n');
            if (anchor.cfrSection != null) {
                sb.append("Authority: 38 CFR ").append(anchor.cfrSection);
                String asOf = asOfFor(anchor.code);
                if (asOf != null) sb.append(" (as of ").append(asOf).append(')');
                sb.append('\n')
                  .append(ECFR_SECTION_URL).append(anchor.cfrSection).append('\n');
            }
        } else {
            sb.append("No schedule entry matches '").append(name).append("'.\n");
        }

        String siblingSystem = anchor != null ? anchor.bodySystem : bodySystem;
        List<Entry> siblings = siblingsIn(siblingSystem,
                anchor != null ? anchor.code : vasrdCode);
        if (!siblings.isEmpty()) {
            sb.append("Nearby codes in ").append(displaySystem(siblingSystem)).append(":\n");
            for (Entry s : siblings) {
                sb.append("  ").append(s.code).append("  ").append(s.title).append('\n');
            }
        }
        return sb.toString();
    }

    // ──────────────────────────────────────────────────────────────
    //  Resolution — DB first, bundled index second
    // ──────────────────────────────────────────────────────────────

    private Entry resolve(String name, String vasrdCode) {
        if (vasrdCode != null) {
            Entry fromDb = fromDatabase(vasrdCode);
            if (fromDb != null) return fromDb;
            Entry bundled = byCode.get(vasrdCode);
            if (bundled != null) return bundled;
        }
        // Fall back to an exact title match only when the code missed. By the
        // gap stage a condition normally carries a code, so this is rare.
        List<Entry> named = findByTitle(name);
        return named.isEmpty() ? null : named.get(0);
    }

    private Entry fromDatabase(String code) {
        if (vasrdRecordRepository == null) return null;
        try {
            var rows = vasrdRecordRepository.findByDcCodeOrderByDisplayOrder(code);
            if (rows.isEmpty()) return null;
            var r = rows.get(0);
            return new Entry(r.getDcCode(), r.getTitle(), r.getBodySystem(), r.getCfrSection());
        } catch (Exception e) {
            log.debug("VASRD record lookup failed for {}, using bundled index: {}",
                    code, e.getMessage());
            return null;
        }
    }

    /**
     * Distinct sibling codes in a body system, excluding the anchor. Prefers the
     * ingested table; falls back to the bundled index.
     */
    private List<Entry> siblingsIn(String bodySystem, String excludeCode) {
        if (bodySystem == null || bodySystem.isBlank()) return List.of();

        var out = new LinkedHashMap<String, Entry>();
        if (vasrdRecordRepository != null) {
            try {
                for (var r : vasrdRecordRepository.findDistinctCodesInBodySystem(bodySystem)) {
                    if (r.getDcCode() == null || r.getDcCode().equals(excludeCode)) continue;
                    out.putIfAbsent(r.getDcCode(),
                            new Entry(r.getDcCode(), r.getTitle(), bodySystem, null));
                    if (out.size() >= MAX_SIBLINGS) return List.copyOf(out.values());
                }
            } catch (Exception e) {
                log.debug("VASRD sibling lookup failed for '{}': {}", bodySystem, e.getMessage());
            }
        }
        if (!out.isEmpty()) return List.copyOf(out.values());

        for (Entry e : all) {
            if (e.code == null || e.code.equals(excludeCode)) continue;
            if (!bodySystem.equalsIgnoreCase(e.bodySystem)) continue;
            out.putIfAbsent(e.code, e);
            if (out.size() >= MAX_SIBLINGS) break;
        }
        return List.copyOf(out.values());
    }

    /**
     * The point-in-time date to cite. Only the ingested table carries a real
     * per-section date; the bundled index carries its generation date.
     */
    private String asOfFor(String code) {
        if (code != null && vasrdRecordRepository != null) {
            try {
                var rows = vasrdRecordRepository.findByDcCodeOrderByDisplayOrder(code);
                if (!rows.isEmpty() && rows.get(0).getAsOfDate() != null) {
                    return rows.get(0).getAsOfDate().toString();
                }
            } catch (Exception ignored) {
                // Fall through to the bundled date.
            }
        }
        return bundledAsOf;
    }

    /**
     * Section headings read "Schedule of ratings—respiratory system." Trim the
     * boilerplate so the prompt names the body system, not the CFR's phrasing.
     */
    static String displaySystem(String heading) {
        if (heading == null || heading.isBlank()) return "this body system";
        String s = heading.strip();
        int dash = s.indexOf('—');                    // em dash
        if (dash >= 0 && dash + 1 < s.length()) s = s.substring(dash + 1);
        if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return s.strip();
    }
}
