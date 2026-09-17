package com.afterduty.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.afterduty.model.IdentifiedCondition;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The self-correction knowledge base (owner-curated,
 * {@code resources/domain-corrections.json}). Each entry records an analysis
 * claim that FEEDBACK later disproved — e.g. "diagnosed migraines are Gulf War
 * presumptive" (DC-2026-001, born of the 8100 code-collision bug) — with the
 * authoritative correction.
 *
 * <p>Two enforcement lanes, deliberately redundant:
 * <ol>
 *   <li><b>{@link #enforce}</b> — deterministic. Runs at the synthesis flip
 *       AFTER every lane that can assert a claim (identify LLM, presumptive
 *       rules engine) and repairs matching conditions. This is the guarantee:
 *       a recorded mistake cannot reach a veteran again, no matter which lane
 *       re-makes it.</li>
 *   <li><b>{@link #promptCorpus}</b> — the same entries rendered into the
 *       identify + verification prompts, so the model stops making the claim
 *       in the first place. Optimization only; never load-bearing.</li>
 * </ol>
 *
 * <p>Growing the KB is an owner act: add an entry to the JSON (in-repo, part
 * of the transparency log) and deploy. No LLM writes into this file.
 */
@Service
public class DomainCorrectionsService {

    private static final Logger log = LoggerFactory.getLogger(DomainCorrectionsService.class);

    /** One loaded correction entry; patterns pre-compiled, case-insensitive. */
    record Correction(String id, String title, String correction, String authority,
                      Pattern namePattern, Pattern basisPattern, String action) {}

    private final ObjectMapper mapper = new ObjectMapper();
    private List<Correction> corrections = List.of();

    @PostConstruct
    public void load() {
        try {
            var res = new ClassPathResource("domain-corrections.json");
            Map<String, Object> root = mapper.readValue(res.getInputStream(), new com.fasterxml.jackson.core.type.TypeReference<>() {});
            Object rawList = root.get("corrections");
            List<Correction> loaded = new ArrayList<>();
            if (rawList instanceof List<?> list) {
                for (Object o : list) {
                    if (!(o instanceof Map<?, ?> m)) continue;
                    // Per-entry isolation: one malformed entry (bad regex, missing
                    // field) must not disable the rest of an owner-edited KB.
                    try {
                        Map<?, ?> match = m.get("match") instanceof Map<?, ?> mm ? mm : Map.of();
                        loaded.add(new Correction(
                                str(m.get("id")),
                                str(m.get("title")),
                                str(m.get("correction")),
                                str(m.get("authority")),
                                compile(str(match.get("name_pattern"))),
                                compile(str(match.get("basis_pattern"))),
                                str(m.get("action"))));
                    } catch (Exception entryError) {
                        log.error("Skipping malformed domain-correction entry {} — fix domain-corrections.json",
                                m.get("id"), entryError);
                    }
                }
            }
            corrections = List.copyOf(loaded);
            log.info("Loaded {} domain corrections from domain-corrections.json", corrections.size());
        } catch (Exception e) {
            // A malformed KB must never block startup — the pipeline still runs,
            // just without the corrections. Loudly, so it gets fixed.
            log.error("Failed to load domain-corrections.json — corrections DISABLED", e);
            corrections = List.of();
        }
    }

    private static String str(Object o) {
        return o != null ? o.toString() : "";
    }

    private static Pattern compile(String p) {
        return p.isBlank() ? null : Pattern.compile(p, Pattern.CASE_INSENSITIVE);
    }

    int size() {
        return corrections.size();
    }

    /**
     * Deterministic enforcement: repair every condition matching a correction.
     * For {@code strip_presumptive}: clear the presumptive flag/basis and
     * rewrite the nexus leg back to needs-direct-connection, with the
     * correction (and its authority) as the leg's evidence note — template
     * text over curated facts, no LLM involvement. Returns how many conditions
     * were repaired (mutates in place; callers persist).
     */
    public int enforce(List<IdentifiedCondition> conditions) {
        if (conditions == null || corrections.isEmpty()) return 0;
        int repaired = 0;
        for (IdentifiedCondition c : conditions) {
            for (Correction corr : corrections) {
                if (!"strip_presumptive".equals(corr.action())) continue;
                if (!Boolean.TRUE.equals(c.getIsPresumptive())) continue;
                String name = c.getName() != null ? c.getName() : "";
                // A genuinely undiagnosed-illness condition is a DIFFERENT claim
                // than the diagnosed one a correction targets — never strip it.
                if (name.toLowerCase(java.util.Locale.ROOT).contains("undiagnosed")) continue;
                String basis = c.getPresumptiveBasis() != null ? c.getPresumptiveBasis() : "";
                if (corr.namePattern() == null || !corr.namePattern().matcher(name).find()) continue;
                // A BLANK basis counts as matching: an unexplained presumptive
                // assertion cannot stand against a name-matched recorded
                // correction. A non-blank basis must match the pattern, so a
                // different LEGITIMATE basis is never swept away.
                if (corr.basisPattern() != null && !basis.isBlank()
                        && !corr.basisPattern().matcher(basis).find()) continue;

                c.setIsPresumptive(false);
                c.setPresumptiveBasis(null);
                c.setTriadNexus(correctedNexus(corr));
                repaired++;
                log.info("[corrections] {} applied to condition {} ('{}') — presumptive claim stripped",
                        corr.id(), c.getId(), name);
                break; // one correction per condition is enough
            }
        }
        return repaired;
    }

    /** The nexus leg after a strip: honest needs-evidence state + the why. */
    private static Map<String, Object> correctedNexus(Correction corr) {
        Map<String, Object> nexus = new LinkedHashMap<>();
        nexus.put("status", "WEAK");
        // Deterministic, like the provisional-nexus writer: we are confident the
        // leg is weak (the presumption was disproven), matching the schema shape.
        nexus.put("confidence", 0.9);
        nexus.put("evidence", List.of(
                "Correction " + corr.id() + ": " + corr.correction(),
                "Authority: " + corr.authority(),
                "Service connection needs the standard nexus evidence (medical opinion linking "
                        + "the condition to service, or a secondary theory)."));
        return nexus;
    }

    /**
     * The corrections rendered for the identify / verification prompts:
     * a "known corrections — do not repeat" section, or "" when the KB is
     * empty (prompts stay byte-identical to today).
     */
    public String promptCorpus() {
        if (corrections.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("KNOWN CORRECTIONS (verified against feedback — never repeat these errors):\n");
        for (Correction c : corrections) {
            sb.append("- [").append(c.id()).append("] ").append(c.title()).append(": ")
                    .append(c.correction())
                    .append(" (Authority: ").append(c.authority()).append(")\n");
        }
        return sb.toString();
    }
}
